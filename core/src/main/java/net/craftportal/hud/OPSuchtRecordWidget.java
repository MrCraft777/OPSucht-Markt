package net.craftportal.hud;

import net.labymod.api.client.component.Component;
import net.labymod.api.client.gui.hud.binding.category.HudWidgetCategory;
import net.labymod.api.client.gui.hud.hudwidget.text.TextHudWidget;
import net.labymod.api.client.gui.hud.hudwidget.text.TextHudWidgetConfig;
import net.labymod.api.client.gui.hud.hudwidget.text.TextLine;
import net.labymod.api.client.gui.hud.hudwidget.text.TextLine.State;
import net.labymod.api.client.gui.icon.Icon;
import net.labymod.api.client.resources.ResourceLocation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class OPSuchtRecordWidget extends TextHudWidget<TextHudWidgetConfig> {

  private static final String API_URL = "https://craftportal.net/api/opsucht-record";
  private static final int POLL_INTERVAL_SECONDS = 60;

  private TextLine recordLine;
  private final AtomicReference<String> latestRecord = new AtomicReference<>(null);
  private ScheduledExecutorService scheduler;

  public OPSuchtRecordWidget(HudWidgetCategory category) {
    super("opsucht_record_widget");
    this.setIcon(Icon.texture(ResourceLocation.create("opsucktmarkt", "textures/record_widget.png")));
    this.bindCategory(category);
  }

  @Override
  public void load(TextHudWidgetConfig config) {
    super.load(config);

    this.recordLine = createLine(
        Component.translatable("opsuchtmarkt.hudWidget.record_widget.name"),
        Component.translatable("opsuchtmarkt.messages.loading")
    );

    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "OPSuchtRecord-API-Poller");
      t.setDaemon(true);
      return t;
    });

    this.scheduler.scheduleAtFixedRate(() -> {
      try {
        String json = fetchUrl(API_URL);
        String parsed = parseRecordFromJson(json);
        latestRecord.set(parsed);
      } catch (Exception e) {
        latestRecord.set(null);
      }
    }, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
  }

  @Override
  public void onTick(boolean isEditorContext) {
    String value = latestRecord.get();

    if (isEditorContext) {
      this.recordLine.updateAndFlush(Component.text("468"));
      this.recordLine.setState(State.VISIBLE);
      return;
    }

    if (value != null) {
      this.recordLine.updateAndFlush(Component.text(value));
      this.recordLine.setState(State.VISIBLE);
    } else {
      this.recordLine.setState(State.HIDDEN);
    }
  }

  public void shutdown() {
    if (this.scheduler != null && !this.scheduler.isShutdown()) {
      this.scheduler.shutdownNow();
    }
  }

  private static String fetchUrl(String urlStr) throws Exception {
    HttpURLConnection con = null;
    InputStream is = null;
    try {
      URL url = new URL(urlStr);
      con = (HttpURLConnection) url.openConnection();
      con.setRequestMethod("GET");
      con.setConnectTimeout(5000);
      con.setReadTimeout(5000);
      con.setRequestProperty("User-Agent", "OPSuchtRecord-Widget/1.0");

      int code = con.getResponseCode();
      if (code >= 200 && code < 300) {
        is = con.getInputStream();
      } else {
        is = con.getErrorStream();
        if (is == null) return null;
      }

      BufferedReader br = new BufferedReader(new InputStreamReader(is));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = br.readLine()) != null) sb.append(line);
      return sb.toString();
    } finally {
      try { if (is != null) is.close(); } catch (Exception ignored) {}
      if (con != null) con.disconnect();
    }
  }

  private static String parseRecordFromJson(String json) {
    if (json == null) return null;

    int recordIndex = json.indexOf("\"recordPlayers\"");
    if (recordIndex == -1) {
      recordIndex = json.indexOf("\"recordplayers\"");
    }
    if (recordIndex == -1) {
      recordIndex = json.indexOf("\"players\"");
    }

    if (recordIndex != -1) {
      int colonIndex = json.indexOf(':', recordIndex);
      if (colonIndex != -1) {
        int startIndex = colonIndex + 1;
        int endIndex = json.indexOf(',', startIndex);
        if (endIndex == -1) {
          endIndex = json.indexOf('}', startIndex);
        }
        if (endIndex == -1) {
          endIndex = json.length();
        }

        String numberStr = json.substring(startIndex, endIndex).trim();
        numberStr = numberStr.replace("\"", "").replace("'", "").trim();

        try {
          Long.parseLong(numberStr);
          return numberStr;
        } catch (NumberFormatException e) {
        }
      }
    }

    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      if (Character.isDigit(c)) {
        int start = i;
        while (i < json.length() && Character.isDigit(json.charAt(i))) {
          i++;
        }
        String number = json.substring(start, i);
        if (number.length() > 0) {
          return number;
        }
      }
    }

    return null;
  }
}