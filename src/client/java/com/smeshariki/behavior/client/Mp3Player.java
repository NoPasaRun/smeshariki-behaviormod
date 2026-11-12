package com.smeshariki.behavior.client;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import com.smeshariki.behavior.BehaviorMod;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/**
 * Проигрывает файл .mp3 своим потоком, мимо звукового движка игры (он знает только .ogg): декодирует JLayer и отдаёт звук в OpenAL — в тот же контекст и на то же
 * устройство, что и игра (иначе звук уходит не туда, куда слушает игрок: Java Sound на Linux берёт первую карту ALSA, а не выбранный системой выход, например
 * Bluetooth). Если контекста OpenAL нет, запасной путь — {@link SourceDataLine}.
 * Громкость ({@link #gain}) меняется на лету — её каждый такт ставит {@link Soundtrack}; начать можно с любого места файла ({@code offsetSeconds}): лишние кадры просто
 * декодируются вхолостую.
 */
final class Mp3Player implements Runnable {
	private final Path file;
	private final double offsetSeconds;
	private final Thread thread;
	private volatile boolean stopped, finished;
	private volatile float gain;
	private volatile String error = "";

	Mp3Player(Path file, double offsetSeconds, float gain) {
		this.file = file;
		this.offsetSeconds = Math.max(0, offsetSeconds);
		this.gain = gain;
		this.thread = new Thread(this, "cutscene-music");
		this.thread.setDaemon(true);
	}

	void start() {
		thread.start();
	}

	void gain(float value) {
		gain = value;
	}

	void stop() {
		stopped = true;
	}

	boolean finished() {
		return finished;
	}

	/** Что пошло не так (пусто — всё хорошо). */
	String error() {
		return error;
	}

	/** Куда уходит звук. */
	private interface Sink {
		/** Отдать кадр (всегда по 16 бит); блокируется, пока в очереди нет места; {@code gain} — громкость сейчас. */
		void write(short[] pcm, int n, int channels, int hz, float gain, Mp3Player owner);

		void drain(Mp3Player owner);

		void close();
	}

	/** OpenAL игры: один источник и кольцо буферов по кадру MP3 (около 26 мс). */
	private static final class AlSink implements Sink {
		private static final int BUFFERS = 12;
		private final int source;
		private final int[] buffers = new int[BUFFERS];
		private final java.util.ArrayDeque<Integer> free = new java.util.ArrayDeque<>();
		private int queued;

		AlSink() {
			if (org.lwjgl.openal.ALC10.alcGetCurrentContext() == 0L) throw new IllegalStateException("нет контекста OpenAL (звук игры выключен или ещё не запущен)");
			source = org.lwjgl.openal.AL10.alGenSources();
			org.lwjgl.openal.AL10.alSourcei(source, org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE, org.lwjgl.openal.AL10.AL_TRUE);
			org.lwjgl.openal.AL10.alSource3f(source, org.lwjgl.openal.AL10.AL_POSITION, 0f, 0f, 0f);
			for (int i = 0; i < BUFFERS; i++) {
				buffers[i] = org.lwjgl.openal.AL10.alGenBuffers();
				free.add(buffers[i]);
			}
		}

		private void reclaim() {
			int processed = org.lwjgl.openal.AL10.alGetSourcei(source, org.lwjgl.openal.AL10.AL_BUFFERS_PROCESSED);
			for (int i = 0; i < processed; i++) {
				free.add(org.lwjgl.openal.AL10.alSourceUnqueueBuffers(source));
				queued--;
			}
		}

		private void keepPlaying() {
			if (queued > 0 && org.lwjgl.openal.AL10.alGetSourcei(source, org.lwjgl.openal.AL10.AL_SOURCE_STATE) != org.lwjgl.openal.AL10.AL_PLAYING) org.lwjgl.openal.AL10.alSourcePlay(source);
		}

		@Override
		public void write(short[] pcm, int n, int channels, int hz, float gain, Mp3Player owner) {
			while (!owner.stopped) {
				reclaim();
				if (!free.isEmpty()) break;
				keepPlaying();
				try {
					Thread.sleep(5);
				} catch (InterruptedException e) {
					return;
				}
			}
			if (owner.stopped) return;
			// OpenAL держит громкость не выше 1: что выше — множим сами
			org.lwjgl.openal.AL10.alSourcef(source, org.lwjgl.openal.AL10.AL_GAIN, Math.min(1f, gain));
			float mul = Math.max(1f, gain);
			java.nio.ShortBuffer data = java.nio.ByteBuffer.allocateDirect(n * 2).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
			if (mul > 1f) {
				for (int i = 0; i < n; i++) data.put((short) Math.max(-32768, Math.min(32767, Math.round(pcm[i] * mul))));
			} else {
				data.put(pcm, 0, n);
			}
			data.flip();
			int buffer = free.poll();
			org.lwjgl.openal.AL10.alBufferData(buffer, channels == 1 ? org.lwjgl.openal.AL10.AL_FORMAT_MONO16 : org.lwjgl.openal.AL10.AL_FORMAT_STEREO16, data, hz);
			org.lwjgl.openal.AL10.alSourceQueueBuffers(source, buffer);
			queued++;
			keepPlaying();
			int err = org.lwjgl.openal.AL10.alGetError();
			if (err != org.lwjgl.openal.AL10.AL_NO_ERROR) throw new IllegalStateException("ошибка OpenAL " + err);
		}

		@Override
		public void drain(Mp3Player owner) {
			while (!owner.stopped) {
				reclaim();
				if (queued <= 0) return;
				keepPlaying();
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					return;
				}
			}
		}

		@Override
		public void close() {
			org.lwjgl.openal.AL10.alSourceStop(source);
			org.lwjgl.openal.AL10.alSourcei(source, org.lwjgl.openal.AL10.AL_BUFFER, 0);
			org.lwjgl.openal.AL10.alDeleteSources(source);
			for (int b : buffers) org.lwjgl.openal.AL10.alDeleteBuffers(b);
		}
	}

	/** Запасной путь: Java Sound (устройство по умолчанию ALSA, может быть не тем, что слушает игрок). */
	private static final class JavaSoundSink implements Sink {
		private final SourceDataLine line;
		private byte[] bytes = new byte[0];
		private float smooth;

		JavaSoundSink(int channels, int hz, float gain) throws LineUnavailableException {
			AudioFormat format = new AudioFormat(hz, 16, channels, true, false);
			line = AudioSystem.getSourceDataLine(format);
			line.open(format, Math.max(1 << 15, (int) (format.getFrameRate() * format.getFrameSize() / 4)));
			line.start();
			smooth = gain;
		}

		@Override
		public void write(short[] pcm, int n, int channels, int hz, float gain, Mp3Player owner) {
			if (bytes.length < n * 2) bytes = new byte[n * 2];
			float step = (gain - smooth) / Math.max(1, n);
			for (int i = 0; i < n; i++) {
				smooth += step;
				int v = Math.max(-32768, Math.min(32767, Math.round(pcm[i] * smooth)));
				bytes[i * 2] = (byte) v;
				bytes[i * 2 + 1] = (byte) (v >> 8);
			}
			smooth = gain;
			line.write(bytes, 0, n * 2);
		}

		@Override
		public void drain(Mp3Player owner) {
			line.drain();
		}

		@Override
		public void close() {
			line.stop();
			line.close();
		}
	}

	private static Sink open(int channels, int hz, float gain) throws LineUnavailableException {
		try {
			return new AlSink();
		} catch (RuntimeException | LinkageError e) {
			BehaviorMod.LOGGER.warn("OpenAL игры недоступен для музыки ({}), играю через Java Sound", e.toString());
			return new JavaSoundSink(channels, hz, gain);
		}
	}

	@Override
	public void run() {
		Sink sink = null;
		try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
			Bitstream bits = new Bitstream(in);
			Decoder decoder = new Decoder();
			double skipped = 0;
			Header header;
			while (!stopped && (header = bits.readFrame()) != null) {
				SampleBuffer samples = (SampleBuffer) decoder.decodeFrame(header, bits);
				double frameMs = header.ms_per_frame();
				bits.closeFrame();
				if (skipped < offsetSeconds * 1000.0) {
					skipped += frameMs;
					continue;
				}
				if (sink == null) sink = open(samples.getChannelCount(), samples.getSampleFrequency(), gain);
				sink.write(samples.getBuffer(), samples.getBufferLength(), samples.getChannelCount(), samples.getSampleFrequency(), gain, this);
			}
			if (sink != null && !stopped) sink.drain(this);
		} catch (IOException | BitstreamException | DecoderException | LineUnavailableException | RuntimeException e) {
			error = e.getClass().getSimpleName() + ": " + e.getMessage();
			BehaviorMod.LOGGER.warn("MP3 {} не проигрался", file, e);
		} finally {
			if (sink != null) {
				try {
					sink.close();
				} catch (RuntimeException e) {
					BehaviorMod.LOGGER.warn("Музыка не закрылась как надо", e);
				}
			}
			finished = true;
		}
	}
}
