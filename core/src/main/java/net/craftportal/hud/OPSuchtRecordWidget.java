package net.craftportal.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.gui.hud.binding.category.HudWidgetCategory;
import net.labymod.api.client.gui.hud.hudwidget.text.TextHudWidget;
import net.labymod.api.client.gui.hud.hudwidget.text.TextHudWidgetConfig;
import net.labymod.api.client.gui.hud.hudwidget.text.TextLine;
import net.labymod.api.client.gui.hud.hudwidget.text.TextLine.State;
import net.labymod.api.client.gui.icon.Icon;
import net.labymod.api.client.resources.ResourceLocation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class OPSuchtRecordWidget extends TextHudWidget<TextHudWidgetConfig> {

  private static final String API_URL = "https://craftportal.net/api/opsucht-record";
  private static final int UPDATE_RATE_SECONDS = 60;
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final HttpClient CLIENT = HttpClient.newBuilder()
      .connectTimeout(TIMEOUT)
      .build();
  private static final HttpRequest REQUEST = HttpRequest.newBuilder()
      .uri(URI.create(API_URL))
      .GET()
      .timeout(TIMEOUT)
      .header("Accept", "application/json")
      .build();

  private TextLine line;
  private Component cachedComponent;
  private ScheduledExecutorService scheduler;

  public OPSuchtRecordWidget(HudWidgetCategory category) {
    super("opsucht_record_widget");
    setIcon(Icon.texture(ResourceLocation.create("opsuchtmarkt", "textures/record_widget.png")));
    bindCategory(category);
  }

  @Override
  public void load(TextHudWidgetConfig config) {
    super.load(config);
    line = createLine(
        Component.translatable("opsuchtmarkt.hudWidget.record_widget.name"),
        Component.translatable("opsuchtmarkt.messages.loading")
    );

    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "OPSuchtRecord-Fetcher");
      t.setDaemon(true);
      return t;
    });

    scheduler.scheduleAtFixedRate(this::fetchRecord, 0, UPDATE_RATE_SECONDS, TimeUnit.SECONDS);
  }

  private void fetchRecord() {
    try {
      HttpResponse<String> response = CLIENT.send(REQUEST, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        cachedComponent = null;
        return;
      }

      String json = response.body();
      JsonElement root = JsonParser.parseString(json);

      String recordValue = null;
      if (root != null && root.isJsonObject()) {
        JsonElement recordPlayers = root.getAsJsonObject().get("recordPlayers");
        if (recordPlayers == null) {
          recordPlayers = root.getAsJsonObject().get("players");
        }

        if (recordPlayers != null && recordPlayers.isJsonPrimitive() && recordPlayers.getAsJsonPrimitive().isNumber()) {
          recordValue = recordPlayers.getAsString();
        } else if (root.isJsonPrimitive() && root.getAsJsonPrimitive().isNumber()) {
          recordValue = root.getAsString();
        }
      }

      if (recordValue != null) {
        Component newComponent = Component.text(recordValue);
        if (!newComponent.equals(cachedComponent)) {
          cachedComponent = newComponent;
        }
      } else {
        cachedComponent = null;
      }

    } catch (Exception e) {
      cachedComponent = null;
    }
  }

  @Override
  public void onTick(boolean editor) {
    if (editor) {
      line.updateAndFlush(Component.text("468"));
      line.setState(State.VISIBLE);
      return;
    }

    if (cachedComponent != null) {
      line.updateAndFlush(cachedComponent);
      line.setState(State.VISIBLE);
    } else {
      line.setState(State.HIDDEN);
    }
  }
}