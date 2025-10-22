package net.craftportal;

import net.craftportal.config.OPSuchtMarktConfig;
import net.craftportal.hud.OPSuchtMarktWidgets;
import net.craftportal.hud.OPSuchtRecordWidget;
import net.craftportal.hud.OPSuchtInventoryValueWidget;
import net.labymod.api.addon.LabyAddon;
import net.labymod.api.client.gui.hud.binding.category.HudWidgetCategory;
import net.labymod.api.models.addon.annotation.AddonMain;

@AddonMain
public class OPSuchtMarktAddon extends LabyAddon<OPSuchtMarktConfig> {

  private HudWidgetCategory widgetCategory;
  private OPSuchtMarktWidgets widgetInstance;
  private OPSuchtRecordWidget recordWidgetInstance;
  private OPSuchtInventoryValueWidget inventoryValueWidgetInstance;

  @Override
  protected void enable() {
    registerSettingCategory();

    this.widgetCategory = new HudWidgetCategory("opsuchtmarkt");
    labyAPI().hudWidgetRegistry().categoryRegistry().register(widgetCategory);

    // Originales Item-Preis Widget
    this.widgetInstance = new OPSuchtMarktWidgets(widgetCategory, this.configuration());
    labyAPI().hudWidgetRegistry().register(this.widgetInstance);

    // Record Widget (falls vorhanden)
    this.recordWidgetInstance = new OPSuchtRecordWidget(widgetCategory);
    labyAPI().hudWidgetRegistry().register(this.recordWidgetInstance);

    // Neues Inventory Value Widget
    this.inventoryValueWidgetInstance = new OPSuchtInventoryValueWidget(widgetCategory,
        this.configuration());
    labyAPI().hudWidgetRegistry().register(this.inventoryValueWidgetInstance);
  }

  @Override
  protected Class<? extends OPSuchtMarktConfig> configurationClass() {
    return OPSuchtMarktConfig.class;
  }
}