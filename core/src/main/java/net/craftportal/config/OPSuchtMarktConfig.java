package net.craftportal.config;

import net.labymod.api.addon.AddonConfig;
import net.labymod.api.client.gui.screen.widget.widgets.input.SwitchWidget.SwitchSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.color.ColorPickerWidget.ColorPickerSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.dropdown.DropdownWidget.DropdownSetting;
import net.labymod.api.configuration.loader.annotation.ConfigName;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.labymod.api.util.Color;

@ConfigName("settings")
public class OPSuchtMarktConfig extends AddonConfig {

  @SwitchSetting
  private final ConfigProperty<Boolean> enabled = ConfigProperty.create(true);

  @DropdownSetting
  private final ConfigProperty<DisplayMode> displayMode = ConfigProperty.createEnum(DisplayMode.BOTH);

  @SwitchSetting
  private final ConfigProperty<Boolean> useStackSize = ConfigProperty.create(true);

  @SwitchSetting
  private final ConfigProperty<Boolean> showItemName = ConfigProperty.create(true);

  @SwitchSetting
  private final ConfigProperty<Boolean> showItemCount = ConfigProperty.create(true);

  @ColorPickerSetting
  private final ConfigProperty<Color> buyColor = ConfigProperty.create(Color.ofRGB(85, 255, 255));

  @ColorPickerSetting
  private final ConfigProperty<Color> sellColor = ConfigProperty.create(Color.ofRGB(255, 85, 85));

  @DropdownSetting
  private final ConfigProperty<AuctionDisplayCount> auctionDisplayCount = ConfigProperty.createEnum(AuctionDisplayCount.TEN);

  @DropdownSetting
  private final ConfigProperty<AuctionCategory> auctionCategory = ConfigProperty.createEnum(AuctionCategory.TOP);

  @Override
  public ConfigProperty<Boolean> enabled() {
    return enabled;
  }

  public ConfigProperty<DisplayMode> displayMode() {
    return displayMode;
  }

  public ConfigProperty<Boolean> useStackSize() {
    return useStackSize;
  }

  public ConfigProperty<Boolean> showItemName() {
    return showItemName;
  }

  public ConfigProperty<Boolean> showItemCount() {
    return showItemCount;
  }

  public ConfigProperty<Color> buyColor() {
    return buyColor;
  }

  public ConfigProperty<Color> sellColor() {
    return sellColor;
  }

  public ConfigProperty<AuctionDisplayCount> auctionDisplayCount() {
    return auctionDisplayCount;
  }

  public ConfigProperty<AuctionCategory> auctionCategory() {
    return auctionCategory;
  }

  public enum DisplayMode {
    BUY("opsuchtmarkt.settings.displayMode.buy"),
    SELL("opsuchtmarkt.settings.displayMode.sell"),
    BOTH("opsuchtmarkt.settings.displayMode.both");

    private final String translationKey;

    DisplayMode(String translationKey) {
      this.translationKey = translationKey;
    }

    public String getTranslationKey() {
      return translationKey;
    }
  }

  public enum AuctionDisplayCount {
    FIVE("opsuchtmarkt.settings.auctionDisplayCount.five", 5),
    TEN("opsuchtmarkt.settings.auctionDisplayCount.ten", 10),
    FIFTEEN("opsuchtmarkt.settings.auctionDisplayCount.fifteen", 15);

    private final String translationKey;
    private final int count;

    AuctionDisplayCount(String translationKey, int count) {
      this.translationKey = translationKey;
      this.count = count;
    }

    public String getTranslationKey() {
      return translationKey;
    }

    public int getCount() {
      return count;
    }
  }

  public enum AuctionCategory {
    TOP("opsuchtmarkt.settings.auctionCategory.top", null),
    BLOCKS("opsuchtmarkt.settings.auctionCategory.blocks", "blocks"),
    ITEMS("opsuchtmarkt.settings.auctionCategory.items", "items"),
    CUSTOM_ITEMS("opsuchtmarkt.settings.auctionCategory.customItems", "custom_items"),
    OP_ITEMS("opsuchtmarkt.settings.auctionCategory.opItems", "op_items"),
    SPAWNER("opsuchtmarkt.settings.auctionCategory.spawner", "spawner");

    private final String translationKey;
    private final String apiValue;

    AuctionCategory(String translationKey, String apiValue) {
      this.translationKey = translationKey;
      this.apiValue = apiValue;
    }

    public String getTranslationKey() {
      return translationKey;
    }

    public String getApiValue() {
      return apiValue;
    }
  }
}