package com.smeshariki.behavior;

import com.smeshariki.behavior.companion.Companions;
import com.smeshariki.behavior.cutscene.Cutscenes;
import com.smeshariki.behavior.dialog.Dialogs;
import com.smeshariki.behavior.order.BuiltinOrders;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Поведение персонажей: напарник ({@link Companions}), приказы ему ({@link com.smeshariki.behavior.order.Orders}), окно реплик ({@link Dialogs}). */
public class BehaviorMod implements ModInitializer {
	public static final String NAMESPACE = "behaviormod";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAMESPACE);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(NAMESPACE, path);
	}

	@Override
	public void onInitialize() {
		Companions.init();
		BuiltinOrders.init();
		Dialogs.init();
		Cutscenes.init();
	}
}
