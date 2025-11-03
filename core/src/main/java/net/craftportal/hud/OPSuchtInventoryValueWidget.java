package net.craftportal.hud;

import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.TextColor;
import net.labymod.api.client.entity.player.Inventory;
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
import java.net.http.HttpResponse.BodyHandlers;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static net.labymod.api.Laby.labyAPI;

public class OPSuchtInventoryValueWidget extends TextHudWidget<TextHudWidgetConfig> {

  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_2)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private static final long INVENTORY_CHECK_INTERVAL_MS = 2000;
  private static final long PRICE_CACHE_DURATION_MS = 30000;
  private static final long CACHE_CLEANUP_INTERVAL_MS = 300000;
  private static final long MARKET_CACHE_MS = 30000;
  private static final String CURRENCY_SYMBOL = "$";

  private final AtomicReference<InventoryValueData> currentInventoryValue = new AtomicReference<>(null);

  private TextLine valueLine;
  private long lastInventoryCheck = 0L;

  private ScheduledExecutorService executor = null;
  private final Object executorLock = new Object();

  private final ConcurrentHashMap<String, CachedPriceData> priceCache = new ConcurrentHashMap<>();
  private final AtomicReference<JsonObject> marketJsonCache = new AtomicReference<>(null);
  private volatile long marketCacheTimestamp = 0L;

  private final OPSuchtMarktConfig config;

  private final Component loadingComponent;
  private final Component calculatingComponent;
  private final Component noItemsComponent;
  private final Component separatorComponent;

  private TextColor buyColorCache;
  private TextColor sellColorCache;
  private net.labymod.api.util.Color lastConfigBuyColor;
  private net.labymod.api.util.Color lastConfigSellColor;

  private NumberFormat numberFormat;

  private volatile boolean pendingMarketRefresh = false;
  private volatile boolean isCalculating = false;
  private volatile boolean needsDisplayUpdate = false;

  private DisplayMode lastDisplayMode;
  private boolean lastShowItemCount;

  private record InventoryValueData(int totalItems, double totalBuyValue, double totalSellValue, boolean calculationComplete) {}
  private record PriceData(boolean itemNotFound, Double buy, Double sell) {}
  private record CachedPriceData(PriceData priceData, long timestamp) {}

  public OPSuchtInventoryValueWidget(HudWidgetCategory category, OPSuchtMarktConfig config) {
    super("inventory_widget");
    this.config = config;
    this.loadingComponent = Component.translatable("opsuchtmarkt.messages.loading");
    this.calculatingComponent = Component.translatable("opsuchtmarkt.messages.calculating");
    this.noItemsComponent = Component.translatable("opsuchtmarkt.messages.noItem");
    this.separatorComponent = Component.text(" - ").color(TextColor.color(170, 170, 170));
    try {
      this.setIcon(Icon.texture(ResourceLocation.create("opsuchtmarkt", "textures/inventory_value_widget.png")));
    } catch (Throwable t) {
    }

    try {
      this.numberFormat = NumberFormat.getNumberInstance(Locale.getDefault());
    } catch (Exception e) {
      this.numberFormat = NumberFormat.getNumberInstance(Locale.GERMAN);
    }

    initializeColorCache();
    this.lastShowItemCount = this.config.showItemCount().get();
    this.lastDisplayMode = this.config.displayMode().get();
    this.bindCategory(category);
  }

  @Override
  public void load(TextHudWidgetConfig config) {
    super.load(config);
    this.valueLine = createLine(Component.translatable("opsuchtmarkt.hudWidget.inventory_widget.name"), this.loadingComponent);
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

    boolean buyChanged = !currentBuyColor.equals(this.lastConfigBuyColor);
    boolean sellChanged = !currentSellColor.equals(this.lastConfigSellColor);

    if (buyChanged || sellChanged) {
      this.lastConfigBuyColor = currentBuyColor;
      this.lastConfigSellColor = currentSellColor;
      this.buyColorCache = TextColor.color(currentBuyColor.getRed(), currentBuyColor.getGreen(), currentBuyColor.getBlue());
      this.sellColorCache = TextColor.color(currentSellColor.getRed(), currentSellColor.getGreen(), currentSellColor.getBlue());
      needsDisplayUpdate = true;
    }
  }

  @Override
  public void onTick(boolean isEditorContext) {
    if (isEditorContext) {
      this.valueLine.updateAndFlush(this.loadingComponent);
      this.valueLine.setState(State.VISIBLE);
      return;
    }

    if (this.config == null || !this.config.enabled().get()) {
      this.valueLine.setState(State.HIDDEN);
      stopExecutorIfRunning();
      return;
    }

    ensureExecutorRunning();
    updateColorCacheIfNeeded();

    long now = System.currentTimeMillis();
    if (now - lastInventoryCheck > INVENTORY_CHECK_INTERVAL_MS && !isCalculating) {
      lastInventoryCheck = now;
      calculateInventoryValue();
    }

    DisplayMode currentDisplayMode = this.config.displayMode().get();
    boolean currentShowItemCount = this.config.showItemCount().get();

    if (needsDisplayUpdate || currentDisplayMode != lastDisplayMode || currentShowItemCount != lastShowItemCount) {
      updateDisplay(currentDisplayMode, currentShowItemCount);
      lastDisplayMode = currentDisplayMode;
      lastShowItemCount = currentShowItemCount;
      needsDisplayUpdate = false;
    }
  }

  private void updateDisplay(DisplayMode displayMode, boolean showItemCount) {
    InventoryValueData valueData = currentInventoryValue.get();
    Component displayText = buildDisplayText(valueData, displayMode, showItemCount);
    this.valueLine.updateAndFlush(displayText);
    this.valueLine.setState(State.VISIBLE);
  }

  private Component buildDisplayText(InventoryValueData valueData, DisplayMode displayMode, boolean showItemCount) {
    if (marketJsonCache.get() == null) {
      return this.loadingComponent;
    }
    if (valueData == null || !valueData.calculationComplete) {
      return this.calculatingComponent;
    }
    if (valueData.totalItems == 0) {
      return this.noItemsComponent;
    }

    Component valueText = createValueText(valueData.totalBuyValue, valueData.totalSellValue, displayMode);

    if (showItemCount) {
      String itemCountText = valueData.totalItems + " " + net.labymod.api.util.I18n.translate("opsuchtmarkt.messages.items");
      return Component.empty().append(Component.text(itemCountText)).append(this.separatorComponent).append(valueText);
    } else {
      return valueText;
    }
  }

  private Component createValueText(Double totalBuyValue, Double totalSellValue, DisplayMode mode) {
    switch (mode) {
      case BUY:
        if (totalBuyValue != null && totalBuyValue > 0) {
          return Component.text(CURRENCY_SYMBOL + numberFormat.format(totalBuyValue)).color(buyColorCache);
        } else {
          return Component.translatable("opsuchtmarkt.messages.noBuyPrice").color(buyColorCache);
        }
      case SELL:
        if (totalSellValue != null && totalSellValue > 0) {
          return Component.text(CURRENCY_SYMBOL + numberFormat.format(totalSellValue)).color(sellColorCache);
        } else {
          return Component.translatable("opsuchtmarkt.messages.noSellPrice").color(sellColorCache);
        }
      case BOTH:
        Component buyComp = (totalBuyValue != null && totalBuyValue > 0) ? Component.text(CURRENCY_SYMBOL + numberFormat.format(totalBuyValue)).color(buyColorCache) : Component.translatable("opsuchtmarkt.messages.noPrice").color(buyColorCache);
        Component sellComp = (totalSellValue != null && totalSellValue > 0) ? Component.text(CURRENCY_SYMBOL + numberFormat.format(totalSellValue)).color(sellColorCache) : Component.translatable("opsuchtmarkt.messages.noPrice").color(sellColorCache);
        return Component.empty().append(buyComp).append(this.separatorComponent).append(sellComp);
      default:
        return Component.translatable("opsuchtmarkt.messages.noPrice");
    }
  }

  private void calculateInventoryValue() {
    if (isCalculating) return;
    isCalculating = true;

    executor.submit(() -> {
      try {
        if (labyAPI().minecraft() == null || labyAPI().minecraft().getClientPlayer() == null) {
          currentInventoryValue.set(new InventoryValueData(0, 0.0, 0.0, true));
          return;
        }
        Inventory inventory = labyAPI().minecraft().getClientPlayer().inventory();
        if (inventory == null) {
          currentInventoryValue.set(new InventoryValueData(0, 0.0, 0.0, true));
          return;
        }

        JsonObject market = marketJsonCache.get();
        if (market == null) {
          currentInventoryValue.set(new InventoryValueData(0, 0.0, 0.0, false));
          return;
        }

        int totalItems = 0;
        double totalBuyValue = 0.0;
        double totalSellValue = 0.0;

        for (int slot = 0; slot < 36; slot++) {
          ItemStack itemStack = inventory.itemStackAt(slot);
          if (itemStack == null || itemStack.isAir()) {
            continue;
          }
          String itemId = getItemIdFromIdentifier(itemStack);
          if (itemId == null || itemId.isEmpty()) {
            continue;
          }

          int stackSize = getStackSize(itemStack);
          totalItems += stackSize;
          PriceData priceData = getPriceDataForItem(itemId, market);

          if (priceData != null && !priceData.itemNotFound) {
            if (priceData.buy != null) {
              totalBuyValue += priceData.buy * stackSize;
            }
            if (priceData.sell != null) {
              totalSellValue += priceData.sell * stackSize;
            }
          }
        }
        currentInventoryValue.set(new InventoryValueData(totalItems, totalBuyValue, totalSellValue, true));
      } catch (Throwable t) {
        currentInventoryValue.set(new InventoryValueData(0, 0.0, 0.0, true));
      } finally {
        isCalculating = false;
        needsDisplayUpdate = true;
      }
    });
  }

  private PriceData getPriceDataForItem(String itemId, JsonObject market) {
    CachedPriceData cached = priceCache.get(itemId);
    if (cached != null && System.currentTimeMillis() - cached.timestamp < PRICE_CACHE_DURATION_MS) {
      return cached.priceData;
    }

    try {
      PriceData pd = findItemInCategories(market, itemId);
      if (pd != null) {
        priceCache.put(itemId, new CachedPriceData(pd, System.currentTimeMillis()));
        return pd;
      } else {
        PriceData notFoundData = new PriceData(true, null, null);
        priceCache.put(itemId, new CachedPriceData(notFoundData, System.currentTimeMillis()));
        return notFoundData;
      }
    } catch (Exception e) {
      return null;
    }
  }

  private void refreshMarketDataIfNeeded() {
    if (System.currentTimeMillis() - marketCacheTimestamp < MARKET_CACHE_MS || pendingMarketRefresh) {
      return;
    }
    pendingMarketRefresh = true;

    String apiUrl = "https://api.opsucht.net/market/prices";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(apiUrl))
        .timeout(Duration.ofSeconds(5))
        .header("User-Agent", "LabyMod-OPSuchtMarkt/1.0")
        .GET()
        .build();

    HTTP_CLIENT.sendAsync(request, BodyHandlers.ofString())
        .whenCompleteAsync((resp, ex) -> {
          try {
            if (ex == null && resp.statusCode() == 200) {
              String body = resp.body();
              if (body != null && !body.isEmpty()) {
                marketJsonCache.set(JsonParser.parseString(body).getAsJsonObject());
                marketCacheTimestamp = System.currentTimeMillis();
                needsDisplayUpdate = true;
              }
            }
          } catch (Exception e) {
          } finally {
            pendingMarketRefresh = false;
          }
        }, executor);
  }

  private PriceData findItemInCategories(JsonObject categories, String itemId) {
    try {
      for (String categoryName : categories.keySet()) {
        JsonObject category = categories.getAsJsonObject(categoryName);
        if (category.has(itemId)) {
          JsonArray priceArray = category.getAsJsonArray(itemId);
          return parsePriceDataFromArray(priceArray);
        }
      }
    } catch (Exception e) {
    }
    return null;
  }

  private PriceData parsePriceDataFromArray(JsonArray priceArray) {
    if (priceArray == null || priceArray.isEmpty()) {
      return new PriceData(true, null, null);
    }
    Double buyPrice = null;
    Double sellPrice = null;
    for (JsonElement element : priceArray) {
      try {
        JsonObject order = element.getAsJsonObject();
        String orderSide = order.get("orderSide").getAsString();
        double price = order.get("price").getAsDouble();
        if ("BUY".equals(orderSide)) {
          buyPrice = price;
        } else if ("SELL".equals(orderSide)) {
          sellPrice = price;
        }
      } catch (Exception e) {
      }
    }
    return new PriceData(false, buyPrice, sellPrice);
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
        return loc.getPath().toUpperCase(Locale.ROOT);
      }
    } catch (Exception e) {
    }
    return null;
  }

  private void cleanupOldCacheEntries() {
    long now = System.currentTimeMillis();
    priceCache.entrySet().removeIf(e -> now - e.getValue().timestamp > PRICE_CACHE_DURATION_MS * 2);
  }

  private void ensureExecutorRunning() {
    synchronized (executorLock) {
      if (executor == null || executor.isShutdown() || executor.isTerminated()) {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
          Thread t = new Thread(r, "OPSuchtMarkt-Inventory-Worker");
          t.setDaemon(true);
          return t;
        });
        executor.scheduleAtFixedRate(this::cleanupOldCacheEntries, CACHE_CLEANUP_INTERVAL_MS, CACHE_CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(this::refreshMarketDataIfNeeded, 0, MARKET_CACHE_MS, TimeUnit.MILLISECONDS);
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
      marketJsonCache.set(null);
      marketCacheTimestamp = 0L;
      currentInventoryValue.set(null);
      pendingMarketRefresh = false;
      isCalculating = false;
      needsDisplayUpdate = false;
    }
  }
}