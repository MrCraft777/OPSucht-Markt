package net.craftportal.hud;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
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
import net.craftportal.config.OPSuchtMarktConfig.AuctionCategory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

  private final AtomicReference<List<AuctionData>> currentAuctions = new AtomicReference<>(null);
  private List<AuctionData> lastDisplayedAuctions = null;
  private int lastDisplayedCount = -1;

  private final List<TextLine> auctionLines = new ArrayList<>();

  private ScheduledExecutorService executor = null;
  private final Object executorLock = new Object();

  private static final long UPDATE_INTERVAL_MS = 15000;
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
  private final Component unknownComponent;
  private final Component currencyComponent;

  private final HttpClient httpClient;
  private final Gson gson = new Gson();
  private final Type auctionListType = new TypeToken<List<AuctionResponse>>() {}.getType();

  public AuctionHouseWidget(HudWidgetCategory category, OPSuchtMarktConfig config) {
    super("auction_house");
    this.config = config;

    this.loadingComponent = Component.translatable("opsuchtmarkt.messages.loading");
    this.headerComponent = Component.translatable("opsuchtmarkt.hudWidget.auction_house.name");
    this.separatorComponent = Component.text(" - ").color(SEPARATOR_COLOR);
    this.noAuctionsComponent = Component.translatable("opsuchtmarkt.auction.noAuctions");
    this.unknownComponent = Component.translatable("opsuchtmarkt.messages.unknown");
    this.currencyComponent = Component.translatable("opsuchtmarkt.currencySymbol");

    this.httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    try {
      this.setIcon(Icon.texture(ResourceLocation.create("opsuchtmarkt",
          "textures/auction_widget.png")));
    } catch (Throwable ignored) {
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
      currentAuctions.set(null);
      lastDisplayedAuctions = null;
      stopExecutorIfRunning();
    }

    ensureExecutorRunning();
    updateDisplay();
  }

  private void showEditorPreview() {
    this.headerLine.updateAndFlush(this.headerComponent);
    this.headerLine.setState(State.VISIBLE);

    ensureLineCount(2);

    TextLine firstLine = auctionLines.get(0);
    firstLine.updateAndFlush(Component.text("Diamond - $1,000,000 - 2h 30m"));
    firstLine.setState(State.VISIBLE);

    TextLine secondLine = auctionLines.get(1);
    secondLine.updateAndFlush(Component.text("Emerald - $500,000 - 1h 15m"));
    secondLine.setState(State.VISIBLE);

    for (int i = 2; i < auctionLines.size(); i++) {
      auctionLines.get(i).setState(State.HIDDEN);
    }
  }

  private void updateDisplay() {
    List<AuctionData> auctions = currentAuctions.get();
    int displayCount = config.auctionDisplayCount().get().getCount();

    if (auctions == lastDisplayedAuctions && displayCount == lastDisplayedCount) {
      return;
    }
    lastDisplayedAuctions = auctions;
    lastDisplayedCount = displayCount;

    if (auctions == null) {
      this.headerLine.updateAndFlush(this.loadingComponent);
      this.headerLine.setState(State.VISIBLE);
      hideAuctionLines();
      return;
    }

    this.headerLine.updateAndFlush(this.headerComponent);
    this.headerLine.setState(State.VISIBLE);

    if (auctions.isEmpty()) {
      ensureLineCount(1);
      TextLine firstLine = auctionLines.get(0);
      firstLine.updateAndFlush(this.noAuctionsComponent);
      firstLine.setState(State.VISIBLE);
      for (int i = 1; i < auctionLines.size(); i++) {
        auctionLines.get(i).setState(State.HIDDEN);
      }
      return;
    }

    int actualCount = Math.min(displayCount, auctions.size());
    ensureLineCount(actualCount);

    for (int i = 0; i < actualCount; i++) {
      AuctionData auction = auctions.get(i);
      Component line = buildAuctionLine(auction);
      TextLine textLine = auctionLines.get(i);
      textLine.updateAndFlush(line);
      textLine.setState(State.VISIBLE);
    }

    for (int i = actualCount; i < auctionLines.size(); i++) {
      auctionLines.get(i).setState(State.HIDDEN);
    }
  }

  private Component buildAuctionLine(AuctionData auction) {
    Component nameComp;
    if (auction.displayName != null && !auction.displayName.isEmpty()) {
      nameComp = Component.text(auction.displayName);
    } else if (auction.material != null && !auction.material.isEmpty()) {
      nameComp = Component.text(formatMaterialName(auction.material));
    } else {
      nameComp = this.unknownComponent;
    }

    if (auction.amount > 1) {
      nameComp = nameComp.append(Component.text(" x" + auction.amount));
    }

    Component priceComp = Component.empty()
        .append(this.currencyComponent)
        .append(Component.text(numberFormat.format(auction.currentBid)))
        .color(PRICE_COLOR);

    String timeStr = formatTimeRemaining(auction.endTime);
    Component timeComp = Component.text(timeStr).color(TIME_COLOR);

    return Component.empty()
        .append(nameComp)
        .append(this.separatorComponent)
        .append(priceComp)
        .append(this.separatorComponent)
        .append(timeComp);
  }

  private String formatMaterialName(String material) {
    String[] parts = material.toLowerCase(Locale.ROOT).split("_");
    StringBuilder localBuilder = new StringBuilder(64);

    for (int i = 0; i < parts.length; i++) {
      String part = parts[i];
      if (i > 0) {
        localBuilder.append(" ");
      }
      if (!part.isEmpty()) {
        localBuilder.append(Character.toUpperCase(part.charAt(0)));
        if (part.length() > 1) {
          localBuilder.append(part.substring(1));
        }
      }
    }
    return localBuilder.toString();
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
      String apiUrl = (category == AuctionCategory.TOP)
          ? "https://api.opsucht.net/auctions/active"
          : "https://api.opsucht.net/auctions/categories/" + category.getApiValue();

      String resp = httpGet(apiUrl);

      if (resp == null || resp.isEmpty()) {
        currentAuctions.set(new ArrayList<>());
        return;
      }

      List<AuctionResponse> responses = gson.fromJson(resp, auctionListType);
      if (responses == null) {
        currentAuctions.set(new ArrayList<>());
        return;
      }

      List<AuctionData> auctions = new ArrayList<>();
      int displayCount = config.auctionDisplayCount().get().getCount();

      for (AuctionResponse res : responses) {
        if (auctions.size() >= displayCount) {
          break;
        }

        if (res.item != null && res.item.material != null && res.endTime != null) {
          auctions.add(new AuctionData(
              res.item.material,
              res.item.amount,
              res.item.displayName,
              res.currentBid,
              res.endTime
          ));
        }
      }
      currentAuctions.set(auctions);

    } catch (Exception e) {
      currentAuctions.set(new ArrayList<>());
    }
  }

  private String httpGet(String urlStr) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(urlStr))
        .timeout(Duration.ofSeconds(5))
        .header("User-Agent", "LabyMod-OPSuchtMarkt/1.0")
        .GET()
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    return (response.statusCode() == 200) ? response.body() : null;
  }

  private static class AuctionItem {
    String material;
    int amount = 1;
    String displayName;
  }

  private static class AuctionResponse {
    AuctionItem item;
    double currentBid;
    String endTime;
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