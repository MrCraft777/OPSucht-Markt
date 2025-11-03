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
import net.labymod.api.client.world.item.ItemStack;
import net.craftportal.config.OPSuchtMarktConfig;
import net.craftportal.config.OPSuchtMarktConfig.DisplayMode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static net.labymod.api.Laby.labyAPI;

public class OPSuchtMarktWidgets extends TextHudWidget<TextHudWidgetConfig> {

  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_2)
      .connectTimeout(Duration.ofSeconds(3))
      .build();

  private static final long AUTO_DETECT_INTERVAL_MS = 200;
  private static final long PRICE_CACHE_DURATION_MS = 30000;
  private static final long CACHE_CLEANUP_INTERVAL_MS = 300000;
  private static final TextColor SEPARATOR_COLOR = TextColor.color(170, 170, 170);
  private static final String CURRENCY_SYMBOL = "$";

  private final AtomicReference<ItemData> currentItem = new AtomicReference<>(null);

  private TextLine nameLine;
  private long lastAutoDetect = 0L;

  private ScheduledExecutorService executor = null;
  private final Object executorLock = new Object();

  private final ConcurrentHashMap<String, CachedPriceData> priceCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Boolean> pendingRequests = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Component> itemNameCache = new ConcurrentHashMap<>();

  private final OPSuchtMarktConfig config;

  private final Component loadingComponent;
  private final Component noItemComponent;
  private final Component noPriceComponent;
  private final Component unknownItemComponent;

  private TextColor buyColorCache;
  private TextColor sellColorCache;
  private net.labymod.api.util.Color lastConfigBuyColor;
  private net.labymod.api.util.Color lastConfigSellColor;

  private final NumberFormat numberFormat = NumberFormat.getNumberInstance(Locale.GERMAN);

  private volatile boolean needsDisplayUpdate = false;
  private DisplayMode lastDisplayMode;
  private boolean lastShowItemName;
  private boolean lastUseStackSize;

  private record ItemData(String id, int stackSize) {}
  private record PriceData(boolean itemNotFound, Double buy, Double sell) {}
  private record CachedPriceData(PriceData priceData, long timestamp) {}

  public OPSuchtMarktWidgets(HudWidgetCategory category, OPSuchtMarktConfig config) {
    super("opsucht_item_price");
    this.config = config;

    this.loadingComponent = Component.translatable("opsuchtmarkt.messages.loading");
    this.noItemComponent = Component.translatable("opsuchtmarkt.messages.noItem");
    this.noPriceComponent = Component.translatable("opsuchtmarkt.messages.noPrice");
    this.unknownItemComponent = Component.translatable("opsuchtmarkt.messages.unknown");

    try {
      this.setIcon(Icon.texture(ResourceLocation.create("opsuchtmarkt",
          "textures/price_widget.png")));
    } catch (Throwable t) {
      // Ignorieren, wenn Icon nicht geladen werden kann
    }

    initializeColorCache();
    this.bindCategory(category);
  }

  @Override
  public void load(TextHudWidgetConfig config) {
    super.load(config);
    this.nameLine = createLine(
        Component.translatable("opsuchtmarkt.hudWidget.opsucht_item_price.name"),
        this.loadingComponent
    );
    ensureExecutorRunning();
  }

  private void initializeColorCache() {
    net.labymod.api.util.Color currentBuyColor = this.config.buyColor().get();
    net.labymod.api.util.Color currentSellColor = this.config.sellColor().get();

    this.lastConfigBuyColor = currentBuyColor;
    this.lastConfigSellColor = currentSellColor;

    this.buyColorCache = TextColor.color(currentBuyColor.getRed(), currentBuyColor.getGreen(), currentBuyColor.getBlue());
    this.sellColorCache = TextColor.color(currentSellColor.getRed(), currentSellColor.getGreen(), currentSellColor.getBlue());
  }

  private void updateColorCacheIfNeeded() {
    net.labymod.api.util.Color currentBuyColor = this.config.buyColor().get();
    net.labymod.api.util.Color currentSellColor = this.config.sellColor().get();

    if (currentBuyColor.equals(this.lastConfigBuyColor) &&
        currentSellColor.equals(this.lastConfigSellColor)) {
      return;
    }

    this.lastConfigBuyColor = currentBuyColor;
    this.lastConfigSellColor = currentSellColor;

    this.buyColorCache = TextColor.color(currentBuyColor.getRed(), currentBuyColor.getGreen(), currentBuyColor.getBlue());
    this.sellColorCache = TextColor.color(currentSellColor.getRed(), currentSellColor.getGreen(), currentSellColor.getBlue());
  }

  @Override
  public void onTick(boolean isEditorContext) {
    if (isEditorContext) {
      this.nameLine.updateAndFlush(this.loadingComponent);
      this.nameLine.setState(State.VISIBLE);
      return;
    }

    if (this.config == null || !this.config.enabled().get()) {
      this.nameLine.setState(State.HIDDEN);
      stopExecutorIfRunning();
      return;
    }

    ensureExecutorRunning();
    updateColorCacheIfNeeded();

    long now = System.currentTimeMillis();
    if (now - lastAutoDetect > AUTO_DETECT_INTERVAL_MS) {
      lastAutoDetect = now;
      tryAutoDetectHeldItem();
    }

    DisplayMode currentDisplayMode = this.config.displayMode().get();
    boolean currentShowItemName = this.config.showItemName().get();
    boolean currentUseStackSize = this.config.useStackSize().get();

    if (needsDisplayUpdate ||
        currentDisplayMode != lastDisplayMode ||
        currentShowItemName != lastShowItemName ||
        currentUseStackSize != lastUseStackSize) {

      updateDisplay(currentDisplayMode, currentShowItemName, currentUseStackSize);

      lastDisplayMode = currentDisplayMode;
      lastShowItemName = currentShowItemName;
      lastUseStackSize = currentUseStackSize;
      needsDisplayUpdate = false;
    }
  }

  private void updateDisplay(DisplayMode displayMode, boolean showItemName, boolean useStackSize) {
    ItemData itemData = currentItem.get();
    Component displayText = buildDisplayText(itemData, displayMode, showItemName, useStackSize);
    this.nameLine.updateAndFlush(displayText);
    this.nameLine.setState(State.VISIBLE);
  }

  private Component buildDisplayText(ItemData itemData, DisplayMode displayMode, boolean showItemName, boolean useStackSize) {
    if (itemData == null || itemData.id == null || itemData.id.isEmpty() || "unknown".equals(itemData.id)) {
      return this.noItemComponent;
    }

    Component displayNameComp = formatItemIdComponent(itemData.id);

    CachedPriceData cached = priceCache.get(itemData.id);
    if (cached != null && System.currentTimeMillis() - cached.timestamp < PRICE_CACHE_DURATION_MS) {
      PriceData priceData = cached.priceData;
      if (priceData.itemNotFound) {
        return Component.translatable("opsuchtmarkt.prices.noPrice");
      } else {
        Component priceText = createPriceText(priceData.buy, priceData.sell,
            itemData.stackSize, useStackSize, displayMode);
        return createDisplayText(displayNameComp, priceText, itemData.stackSize, useStackSize, showItemName);
      }
    }

    return this.loadingComponent;
  }

  private Component createDisplayText(Component displayNameComp, Component priceText, int stackSize, boolean useStackSize, boolean showItemName) {
    if (!showItemName) {
      return priceText;
    }

    Component nameComp = displayNameComp;
    if (useStackSize && stackSize > 1) {
      nameComp = Component.empty()
          .append(displayNameComp)
          .append(Component.text(" (" + stackSize + ")"));
    }

    return Component.empty()
        .append(nameComp)
        .append(Component.text(" - ").color(SEPARATOR_COLOR))
        .append(priceText);
  }

  private Component createPriceText(Double buyPrice, Double sellPrice, int stackSize, boolean useStackSize, DisplayMode mode) {
    int total = useStackSize ? stackSize : 1;

    switch (mode) {
      case BUY:
        if (buyPrice != null && buyPrice > 0) {
          double tb = buyPrice * total;
          return Component.text(CURRENCY_SYMBOL + numberFormat.format(tb)).color(buyColorCache);
        } else {
          return Component.translatable("opsuchtmarkt.prices.noBuyPrice").color(buyColorCache);
        }
      case SELL:
        if (sellPrice != null && sellPrice > 0) {
          double ts = sellPrice * total;
          return Component.text(CURRENCY_SYMBOL + numberFormat.format(ts)).color(sellColorCache);
        } else {
          return Component.translatable("opsuchtmarkt.prices.noSellPrice").color(sellColorCache);
        }
      case BOTH:
        Component b = (buyPrice != null && buyPrice > 0)
            ? Component.text(CURRENCY_SYMBOL + numberFormat.format(buyPrice * total)).color(buyColorCache)
            : Component.translatable("opsuchtmarkt.prices.noBuyPrice").color(buyColorCache);
        Component s = (sellPrice != null && sellPrice > 0)
            ? Component.text(CURRENCY_SYMBOL + numberFormat.format(sellPrice * total)).color(sellColorCache)
            : Component.translatable("opsuchtmarkt.prices.noSellPrice").color(sellColorCache);
        return Component.empty()
            .append(b)
            .append(Component.text(" - ").color(SEPARATOR_COLOR))
            .append(s);
      default:
        return this.noPriceComponent;
    }
  }

  private void tryAutoDetectHeldItem() {
    ItemData newItemData = null;
    try {
      if (labyAPI().minecraft() != null && labyAPI().minecraft().clientPlayer() != null) {
        ItemStack stk = labyAPI().minecraft().clientPlayer().getMainHandItemStack();
        if (stk != null && !stk.isAir()) {
          String id = getItemIdFromIdentifier(stk);
          if (id != null && !id.isEmpty() && !"unknown".equals(id)) {
            newItemData = new ItemData(id, getStackSize(stk));
          }
        }
      }
    } catch (Throwable t) {
      // Ignorieren
    }

    ItemData oldItemData = currentItem.getAndSet(newItemData);

    if (!Objects.equals(oldItemData, newItemData)) {
      needsDisplayUpdate = true;
      if (newItemData != null) {
        loadPriceData(newItemData.id);
      }
    }
  }

  private void loadPriceData(String itemId) {
    CachedPriceData cached = priceCache.get(itemId);
    if (cached != null && System.currentTimeMillis() - cached.timestamp < PRICE_CACHE_DURATION_MS) {
      return;
    }

    if (pendingRequests.putIfAbsent(itemId, Boolean.TRUE) != null) {
      return;
    }

    updatePriceFromAPI(itemId);
  }

  private int getStackSize(ItemStack stack) {
    try {
      return stack.getSize();
    } catch (Throwable t) {
      return 1;
    }
  }

  private String getItemIdFromIdentifier(ItemStack stk) {
    try {
      ResourceLocation loc = stk.getIdentifier();
      if (loc != null) {
        return loc.getPath();
      }
    } catch (Exception e) {
      // Ignorieren
    }
    return "unknown";
  }

  private Component formatItemIdComponent(String itemId) {
    if (itemId == null || itemId.isEmpty() || "unknown".equals(itemId)) {
      return this.unknownItemComponent;
    }

    return itemNameCache.computeIfAbsent(itemId, id -> {
      String[] parts = id.toLowerCase(Locale.ROOT).split("_");
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
      return Component.text(sb.toString());
    });
  }

  private void updatePriceFromAPI(String itemId) {
    String apiUrl = "https://api.opsucht.net/market/price/" + itemId.toUpperCase(Locale.ROOT);
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(apiUrl))
        .timeout(Duration.ofSeconds(3))
        .header("User-Agent", "LabyMod-OPSuchtMarkt/1.0")
        .GET()
        .build();

    HTTP_CLIENT.sendAsync(request, BodyHandlers.ofString())
        .whenCompleteAsync((resp, ex) -> {
          PriceData newPriceData = parsePriceData(itemId, resp, ex);
          priceCache.put(itemId, new CachedPriceData(newPriceData, System.currentTimeMillis()));
          pendingRequests.remove(itemId);
          needsDisplayUpdate = true;
        }, executor);
  }

  private PriceData parsePriceData(String itemId, HttpResponse<String> resp, Throwable ex) {
    try {
      if (ex != null || resp == null) {
        return new PriceData(false, null, null);
      }
      if (resp.statusCode() == 404) {
        return new PriceData(true, null, null);
      }
      if (resp.statusCode() != 200) {
        return new PriceData(false, null, null);
      }

      String body = resp.body();
      if (body == null || body.isEmpty() || body.equals("{}")) {
        return new PriceData(true, null, null);
      }

      JsonObject json = JsonParser.parseString(body).getAsJsonObject();
      JsonArray arr = json.getAsJsonArray(itemId.toUpperCase(Locale.ROOT));

      if (arr == null || arr.size() == 0) {
        return new PriceData(true, null, null);
      }

      Double buy = null, sell = null;
      for (JsonElement e : arr) {
        JsonObject o = e.getAsJsonObject();
        String side = o.get("orderSide").getAsString();
        double p = o.get("price").getAsDouble();
        if ("BUY".equals(side)) buy = p;
        else if ("SELL".equals(side)) sell = p;
      }
      return new PriceData(false, buy, sell);

    } catch (Exception e) {
      return new PriceData(false, null, null);
    }
  }

  private void cleanupOldCacheEntries() {
    long now = System.currentTimeMillis();
    priceCache.entrySet().removeIf(e -> now - e.getValue().timestamp > PRICE_CACHE_DURATION_MS * 2);
    itemNameCache.clear();
  }

  private void ensureExecutorRunning() {
    synchronized (executorLock) {
      if (executor == null || executor.isShutdown() || executor.isTerminated()) {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
          Thread t = new Thread(r, "OPSuchtMarkt-API-Worker");
          t.setDaemon(true);
          return t;
        });
        executor.scheduleAtFixedRate(this::cleanupOldCacheEntries,
            CACHE_CLEANUP_INTERVAL_MS, CACHE_CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
      }
    }
  }

  private void stopExecutorIfRunning() {
    synchronized (executorLock) {
      if (executor != null && !executor.isShutdown()) {
        executor.shutdownNow();
      }
      executor = null;
      priceCache.clear();
      itemNameCache.clear();
      pendingRequests.clear();
    }
  }

  public void shutdown() {
    stopExecutorIfRunning();
  }
}