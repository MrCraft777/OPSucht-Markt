package net.craftportal;

import net.craftportal.config.OPSuchtMarktConfig;
import net.craftportal.hud.OPSuchtMarktWidgets;
import net.craftportal.hud.OPSuchtRecordWidget;
import net.craftportal.hud.OPSuchtInventoryValueWidget;
import net.craftportal.hud.AuctionHouseWidget;
import net.labymod.api.addon.LabyAddon;
import net.labymod.api.client.gui.hud.binding.category.HudWidgetCategory;
import net.labymod.api.models.addon.annotation.AddonMain;

@AddonMain
public class OPSuchtMarktAddon extends LabyAddon<OPSuchtMarktConfig> {

  private HudWidgetCategory widgetCategory;
  private OPSuchtMarktWidgets widgetInstance;
  private OPSuchtRecordWidget recordWidgetInstance;
  private OPSuchtInventoryValueWidget inventoryValueWidgetInstance;
  private AuctionHouseWidget auctionHouseWidgetInstance;

  @Override
  protected void enable() {
    registerSettingCategory();

    this.widgetCategory = new HudWidgetCategory("opsuchtmarkt");
    labyAPI().hudWidgetRegistry().categoryRegistry().register(widgetCategory);

    this.widgetInstance = new OPSuchtMarktWidgets(widgetCategory, this.configuration());
    labyAPI().hudWidgetRegistry().register(this.widgetInstance);

    this.recordWidgetInstance = new OPSuchtRecordWidget(widgetCategory);
    labyAPI().hudWidgetRegistry().register(this.recordWidgetInstance);

    this.inventoryValueWidgetInstance = new OPSuchtInventoryValueWidget(widgetCategory,
        this.configuration());
    labyAPI().hudWidgetRegistry().register(this.inventoryValueWidgetInstance);

    this.auctionHouseWidgetInstance = new AuctionHouseWidget(widgetCategory, this.configuration());
    labyAPI().hudWidgetRegistry().register(this.auctionHouseWidgetInstance);
  }

  @Override
  protected Class<? extends OPSuchtMarktConfig> configurationClass() {
    return OPSuchtMarktConfig.class;
  }
}