package net.craftportal.hud;

import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.TextColor;
import net.labymod.api.client.gui.hud.binding.category.HudWidgetCategory;
import net.labymod.api.client.gui.hud.hudwidget.text.TextHudWidget;
import net.labymod.api.client.gui.hud.hudwidget.text.TextHudWidgetConfig;
import net.labymod.api.client.gui.hud.hudwidget.text.TextLine;
import net.labymod.api.client.gui.hud.hudwidget.text.TextLine.State;
import net.labymod.api.client.gui.icon.Icon;
import net.labymod.api.client.resources.ResourceLocation;
import net.craftportal.config.OPSuchtMarktConfig;
import net.craftportal.config.OPSuchtMarktConfig.AuctionDisplayCount;
import net.craftportal.config.OPSuchtMarktConfig.AuctionCategory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class AuctionHouseWidget extends TextHudWidget<TextHudWidgetConfig> {

  private final AtomicReference<List<AuctionData>> currentAuctions = new AtomicReference<>(new ArrayList<>());
  private final List<TextLine> auctionLines = new ArrayList<>();

  private ScheduledExecutorService executor = null;
  private final Object executorLock = new Object();

  private static final long UPDATE_INTERVAL_MS = 15000;
  private static final String CURRENCY_SYMBOL = "$";
  private static final TextColor TIME_COLOR = TextColor.color(170, 170, 170);
  private static final TextColor PRICE_COLOR = TextColor.color(85, 255, 85);
  private static final TextColor SEPARATOR_COLOR = TextColor.color(170, 170, 170);

  private final NumberFormat numberFormat = NumberFormat.getNumberInstance(Locale.GERMAN);
  private final OPSuchtMarktConfig config;

  private TextLine headerLine;
  private AuctionCategory lastCategory = null;

  private final Component loadingComponent;
  private final Component headerComponent;
  private final Component separatorComponent;
  private final Component noAuctionsComponent;

  private boolean isFirstLoad = true;

  public AuctionHouseWidget(HudWidgetCategory category, OPSuchtMarktConfig config) {
    super("auction_house");
    this.config = config;

    this.loadingComponent = Component.translatable("opsuchtmarkt.messages.loading");
    this.headerComponent = Component.translatable("opsuchtmarkt.hudWidget.auction_house.name");
    this.separatorComponent = Component.text(" - ").color(SEPARATOR_COLOR);
    this.noAuctionsComponent = Component.translatable("opsuchtmarkt.auction.noAuctions");

    try {
      this.setIcon(Icon.texture(ResourceLocation.create("opsuchtmarkt",
          "textures/auction_widget.png")));
    } catch (Throwable t) {
    }

    this.bindCategory(category);
  }

  @Override
  public void load(TextHudWidgetConfig config) {
    super.load(config);
    this.headerLine = createLine(this.headerComponent, this.loadingComponent);
  }

  @Override
  public void onTick(boolean isEditorContext) {
    if (isEditorContext) {
      showEditorPreview();
      return;
    }

    if (this.config == null || !this.config.enabled().get()) {
      hideAllLines();
      stopExecutorIfRunning();
      return;
    }

    AuctionCategory currentCategory = this.config.auctionCategory().get();
    if (lastCategory != currentCategory) {
      lastCategory = currentCategory;
      currentAuctions.set(new ArrayList<>());
      isFirstLoad = true;
      stopExecutorIfRunning();
    }

    ensureExecutorRunning();
    updateDisplay();
  }

  private void showEditorPreview() {
    this.headerLine.updateAndFlush(this.headerComponent);
    this.headerLine.setState(State.VISIBLE);

    ensureLineCount(2);

    auctionLines.get(0).updateAndFlush(Component.text("Diamond - $1,000,000 - 2h 30m"));
    auctionLines.get(0).setState(State.VISIBLE);

    auctionLines.get(1).updateAndFlush(Component.text("Emerald - $500,000 - 1h 15m"));
    auctionLines.get(1).setState(State.VISIBLE);

    for (int i = 2; i < auctionLines.size(); i++) {
      auctionLines.get(i).setState(State.HIDDEN);
    }
  }

  private void updateDisplay() {
    List<AuctionData> auctions = currentAuctions.get();

    if (isFirstLoad && (auctions == null || auctions.isEmpty())) {
      this.headerLine.updateAndFlush(this.loadingComponent);
      this.headerLine.setState(State.VISIBLE);
      hideAuctionLines();
      return;
    }

    this.headerLine.updateAndFlush(this.headerComponent);
    this.headerLine.setState(State.VISIBLE);

    if (auctions == null || auctions.isEmpty()) {
      ensureLineCount(1);
      auctionLines.get(0).updateAndFlush(this.noAuctionsComponent);
      auctionLines.get(0).setState(State.VISIBLE);
      for (int i = 1; i < auctionLines.size(); i++) {
        auctionLines.get(i).setState(State.HIDDEN);
      }
      return;
    }

    int displayCount = config.auctionDisplayCount().get().getCount();
    int actualCount = Math.min(displayCount, auctions.size());

    ensureLineCount(actualCount);

    for (int i = 0; i < actualCount; i++) {
      AuctionData auction = auctions.get(i);
      Component line = buildAuctionLine(auction);
      auctionLines.get(i).updateAndFlush(line);
      auctionLines.get(i).setState(State.VISIBLE);
    }

    for (int i = actualCount; i < auctionLines.size(); i++) {
      auctionLines.get(i).setState(State.HIDDEN);
    }
  }

  private Component buildAuctionLine(AuctionData auction) {
    String itemName = auction.displayName != null && !auction.displayName.isEmpty()
        ? auction.displayName
        : formatMaterialName(auction.material);

    String priceStr = CURRENCY_SYMBOL + numberFormat.format(auction.currentBid);
    String timeStr = formatTimeRemaining(auction.endTime);

    Component nameComp = Component.text(itemName);
    if (auction.amount > 1) {
      nameComp = Component.empty()
          .append(nameComp)
          .append(Component.text(" x" + auction.amount));
    }

    return Component.empty()
        .append(nameComp)
        .append(this.separatorComponent)
        .append(Component.text(priceStr).color(PRICE_COLOR))
        .append(this.separatorComponent)
        .append(Component.text(timeStr).color(TIME_COLOR));
  }

  private String formatMaterialName(String material) {
    if (material == null || material.isEmpty()) {
      return "Unknown";
    }

    String[] parts = material.toLowerCase(Locale.ROOT).split("_");
    StringBuilder sb = new StringBuilder();
    for (String part : parts) {
      if (sb.length() > 0) {
        sb.append(" ");
      }
      if (part.length() >= 1) {
        sb.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
        if (part.length() > 1) {
          sb.append(part.substring(1));
        }
      }
    }
    return sb.toString();
  }

  private String formatTimeRemaining(String endTimeStr) {
    try {
      Instant endTime = Instant.parse(endTimeStr);
      Instant now = Instant.now();
      Duration duration = Duration.between(now, endTime);

      if (duration.isNegative()) {
        return "0m";
      }

      long hours = duration.toHours();
      long minutes = duration.toMinutes() % 60;

      if (hours > 0) {
        return hours + "h " + minutes + "m";
      } else {
        return minutes + "m";
      }
    } catch (Exception e) {
      return "?";
    }
  }

  private void ensureLineCount(int needed) {
    while (auctionLines.size() < needed) {
      auctionLines.add(createLine(Component.empty(), Component.empty()));
    }
  }

  private void hideAllLines() {
    this.headerLine.setState(State.HIDDEN);
    hideAuctionLines();
  }

  private void hideAuctionLines() {
    for (TextLine line : auctionLines) {
      line.setState(State.HIDDEN);
    }
  }

  private void ensureExecutorRunning() {
    synchronized (executorLock) {
      if (executor == null || executor.isShutdown() || executor.isTerminated()) {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
          Thread t = new Thread(r, "OPSuchtMarkt-Auction-Worker");
          t.setDaemon(true);
          return t;
        });

        executor.submit(this::loadAuctions);

        executor.scheduleAtFixedRate(this::loadAuctions,
            UPDATE_INTERVAL_MS, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
      }
    }
  }

  private void stopExecutorIfRunning() {
    synchronized (executorLock) {
      if (executor != null && !executor.isShutdown()) {
        executor.shutdownNow();
      }
      executor = null;
    }
  }

  private void loadAuctions() {
    try {
      AuctionCategory category = config.auctionCategory().get();
      String apiUrl;

      if (category == AuctionCategory.TOP) {
        apiUrl = "https://api.opsucht.net/auctions/active";
      } else {
        apiUrl = "https://api.opsucht.net/auctions/categories/" + category.getApiValue();
      }

      String resp = httpGet(apiUrl);

      if (resp != null && !resp.isEmpty()) {
        JsonElement element = JsonParser.parseString(resp);

        if (!element.isJsonArray()) {
          isFirstLoad = false;
          currentAuctions.set(new ArrayList<>());
          return;
        }

        JsonArray arr = element.getAsJsonArray();
        List<AuctionData> auctions = new ArrayList<>();

        int displayCount = config.auctionDisplayCount().get().getCount();
        int count = 0;

        for (JsonElement elem : arr) {
          if (count >= displayCount) {
            break;
          }

          if (!elem.isJsonObject()) {
            continue;
          }

          try {
            JsonObject obj = elem.getAsJsonObject();

            if (!obj.has("item") || !obj.get("item").isJsonObject()) {
              continue;
            }

            JsonObject item = obj.getAsJsonObject("item");

            String material = item.has("material") ? item.get("material").getAsString() : null;
            int amount = item.has("amount") ? item.get("amount").getAsInt() : 1;
            String displayName = item.has("displayName") ? item.get("displayName").getAsString() : null;

            double currentBid = obj.has("currentBid") ? obj.get("currentBid").getAsDouble() : 0;
            String endTime = obj.has("endTime") ? obj.get("endTime").getAsString() : null;

            if (material != null && endTime != null) {
              auctions.add(new AuctionData(material, amount, displayName, currentBid, endTime));
              count++;
            }
          } catch (Exception e) {
          }
        }

        isFirstLoad = false;
        currentAuctions.set(auctions);
      } else {
        isFirstLoad = false;
        currentAuctions.set(new ArrayList<>());
      }
    } catch (Exception e) {
      isFirstLoad = false;
      currentAuctions.set(new ArrayList<>());
    }
  }

  private String httpGet(String urlStr) {
    HttpURLConnection conn = null;
    try {
      URL url = new URL(urlStr);
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(5000);
      conn.setReadTimeout(5000);
      conn.setRequestProperty("User-Agent", "LabyMod-OPSuchtMarkt/1.0");

      int code = conn.getResponseCode();
      if (code == 200) {
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
          sb.append(line);
        }
        br.close();
        return sb.toString();
      }
    } catch (Exception e) {
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
    return null;
  }

  public void shutdown() {
    stopExecutorIfRunning();
  }

  private static class AuctionData {
    final String material;
    final int amount;
    final String displayName;
    final double currentBid;
    final String endTime;

    AuctionData(String material, int amount, String displayName, double currentBid, String endTime) {
      this.material = material;
      this.amount = amount;
      this.displayName = displayName;
      this.currentBid = currentBid;
      this.endTime = endTime;
    }
  }
}