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

  @ColorPickerSetting
  private final ConfigProperty<Color> buyColor = ConfigProperty.create(Color.ofRGB(85, 255, 255));

  @ColorPickerSetting
  private final ConfigProperty<Color> sellColor = ConfigProperty.create(Color.ofRGB(255, 0, 0));

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

  public ConfigProperty<Color> buyColor() {
    return buyColor;
  }

  public ConfigProperty<Color> sellColor() {
    return sellColor;
  }

  public enum DisplayMode {
    BUY("opsuchtmarkt.displayMode.buy"),
    SELL("opsuchtmarkt.displayMode.sell"),
    BOTH("opsuchtmarkt.displayMode.both");

    private final String translationKey;

    DisplayMode(String translationKey) {
      this.translationKey = translationKey;
    }

    public String getTranslationKey() {
      return translationKey;
    }

    @Override
    public String toString() {
      return net.labymod.api.util.I18n.translate(translationKey);
    }
  }
}