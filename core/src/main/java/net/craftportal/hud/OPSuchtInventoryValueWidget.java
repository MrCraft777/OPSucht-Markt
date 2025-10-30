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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static net.labymod.api.Laby.labyAPI;

public class OPSuchtInventoryValueWidget extends TextHudWidget<TextHudWidgetConfig> {

  private final AtomicReference<InventoryValueData> currentInventoryValue = new AtomicReference<>(null);
  private final AtomicReference<String> lastDisplayedKey = new AtomicReference<>(null);

  private TextLine valueLine;
  private long lastInventoryCheck = 0L;
  private static final long INVENTORY_CHECK_INTERVAL_MS = 2000;

  private ScheduledExecutorService executor = null;
  private final Object executorLock = new Object();

  private final ConcurrentHashMap<String, CachedPriceData> priceCache = new ConcurrentHashMap<>();
  private static final long PRICE_CACHE_DURATION_MS = 30000;
  private static final long CACHE_CLEANUP_INTERVAL_MS = 300000;

  private final OPSuchtMarktConfig config;

  private final Component loadingComponent;
  private final Component calculatingComponent;
  private final Component noItemsComponent;
  private final Component separatorComponent;

  private TextColor buyColorCache;
  private TextColor sellColorCache;
  private int lastBuyColor = -1;
  private int lastSellColor = -1;
  private net.labymod.api.util.Color lastConfigBuyColor;
  private net.labymod.api.util.Color lastConfigSellColor;
  private boolean lastShowItemCount = true;

  private final NumberFormat numberFormat;

  private static final String CURRENCY_SYMBOL = "$";

  private final AtomicReference<JsonObject> marketJsonCache = new AtomicReference<>(null);
  private volatile long marketCacheTimestamp = 0L;
  private static final long MARKET_CACHE_MS = 30000;
  private volatile boolean pendingMarketRefresh = false;
  private volatile boolean isCalculating = false;
  private volatile boolean marketDataLoaded = false;
  private volatile boolean needsDisplayUpdate = false;

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
    this.numberFormat = initializeNumberFormat();
    initializeColorCache();
    this.lastShowItemCount = this.config.showItemCount().get();
    this.bindCategory(category);
  }

  private NumberFormat initializeNumberFormat() {
    Locale locale = Locale.getDefault();
    try {
      return NumberFormat.getNumberInstance(locale);
    } catch (Exception e) {
      return NumberFormat.getNumberInstance(Locale.GERMAN);
    }
  }

  @Override
  public void load(TextHudWidgetConfig config) {
    super.load(config);
    this.valueLine = createLine(Component.translatable("opsuchtmarkt.hudWidget.inventory_widget.name"), this.loadingComponent);
  }

  private void initializeColorCache() {
    net.labymod.api.util.Color currentBuyColor = this.config.buyColor().get();
    net.labymod.api.util.Color currentSellColor = this.config.sellColor().get();
    this.lastConfigBuyColor = currentBuyColor;
    this.lastConfigSellColor = currentSellColor;
    this.buyColorCache = TextColor.color(currentBuyColor.getRed(), currentBuyColor.getGreen(), currentBuyColor.getBlue());
    this.sellColorCache = TextColor.color(currentSellColor.getRed(), currentSellColor.getGreen(), currentSellColor.getBlue());
    this.lastBuyColor = currentBuyColor.getRed() << 16 | currentBuyColor.getGreen() << 8 | currentBuyColor.getBlue();
    this.lastSellColor = currentSellColor.getRed() << 16 | currentSellColor.getGreen() << 8 | currentSellColor.getBlue();
  }

  private void updateColorCacheIfNeeded() {
    net.labymod.api.util.Color currentBuyColor = this.config.buyColor().get();
    net.labymod.api.util.Color currentSellColor = this.config.sellColor().get();
    boolean currentShowItemCount = this.config.showItemCount().get();
    boolean buyColorChanged = currentBuyColor.getRed() != lastConfigBuyColor.getRed() || currentBuyColor.getGreen() != lastConfigBuyColor.getGreen() || currentBuyColor.getBlue() != lastConfigBuyColor.getBlue();
    boolean sellColorChanged = currentSellColor.getRed() != lastConfigSellColor.getRed() || currentSellColor.getGreen() != lastConfigSellColor.getGreen() || currentSellColor.getBlue() != lastConfigSellColor.getBlue();
    boolean showItemCountChanged = currentShowItemCount != lastShowItemCount;
    if (buyColorChanged || sellColorChanged || showItemCountChanged) {
      this.lastConfigBuyColor = currentBuyColor;
      this.lastConfigSellColor = currentSellColor;
      this.lastShowItemCount = currentShowItemCount;
      this.buyColorCache = TextColor.color(currentBuyColor.getRed(), currentBuyColor.getGreen(), currentBuyColor.getBlue());
      this.sellColorCache = TextColor.color(currentSellColor.getRed(), currentSellColor.getGreen(), currentSellColor.getBlue());
      this.lastBuyColor = currentBuyColor.getRed() << 16 | currentBuyColor.getGreen() << 8 | currentBuyColor.getBlue();
      this.lastSellColor = currentSellColor.getRed() << 16 | currentSellColor.getGreen() << 8 | currentSellColor.getBlue();
      lastDisplayedKey.set(null);
    }
  }

  @Override
  public void onTick(boolean isEditorContext) {
    updateColorCacheIfNeeded();
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

    if (!marketDataLoaded && marketJsonCache.get() == null && !pendingMarketRefresh) {
      pendingMarketRefresh = true;
      executor.submit(() -> {
        try {
          loadMarketJsonFromAPI();
          marketDataLoaded = true;
        } finally {
          pendingMarketRefresh = false;
        }
      });
    }

    long now = System.currentTimeMillis();
    if (now - lastInventoryCheck > INVENTORY_CHECK_INTERVAL_MS && !isCalculating) {
      lastInventoryCheck = now;
      calculateInventoryValue();
    }
    updateDisplay();
  }

  private void updateDisplay() {
    InventoryValueData valueData = currentInventoryValue.get();
    DisplayMode currentDisplayMode = this.config.displayMode().get();
    boolean currentShowItemCount = this.config.showItemCount().get();
    String key = (valueData != null ? valueData.hashCode() : "null") + ":" + currentDisplayMode.name() + ":" + currentShowItemCount + ":" + lastBuyColor + ":" + lastSellColor;
    String previousKey = lastDisplayedKey.get();
    if (Objects.equals(key, previousKey) && !needsDisplayUpdate) {
      return;
    }
    Component displayText = buildDisplayText(valueData, currentDisplayMode);
    this.valueLine.updateAndFlush(displayText);
    this.valueLine.setState(State.VISIBLE);
    lastDisplayedKey.set(key);
    needsDisplayUpdate = false;
  }

  private Component buildDisplayText(InventoryValueData valueData, DisplayMode displayMode) {
    if (!marketDataLoaded && marketJsonCache.get() == null) {
      return this.loadingComponent;
    }

    if (valueData == null) {
      return this.calculatingComponent;
    }
    if (valueData.totalItems == 0) {
      return this.noItemsComponent;
    }
    if (!valueData.calculationComplete) {
      return this.calculatingComponent;
    }
    Component valueText = createValueText(valueData.totalBuyValue, valueData.totalSellValue, displayMode);
    if (this.config.showItemCount().get()) {
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
    if (isCalculating) {
      return;
    }
    isCalculating = true;

    executor.submit(() -> {
      try {
        if (labyAPI().minecraft() == null || labyAPI().minecraft().getClientPlayer() == null) {
          currentInventoryValue.set(new InventoryValueData(0, 0.0, 0.0, true));
          needsDisplayUpdate = true;
          return;
        }
        Inventory inventory = labyAPI().minecraft().getClientPlayer().inventory();
        if (inventory == null) {
          currentInventoryValue.set(new InventoryValueData(0, 0.0, 0.0, true));
          needsDisplayUpdate = true;
          return;
        }

        JsonObject market = marketJsonCache.get();
        if (market == null) {
          currentInventoryValue.set(new InventoryValueData(0, 0.0, 0.0, false));
          needsDisplayUpdate = true;
          return;
        }

        int totalItems = 0;
        double totalBuyValue = 0.0;
        double totalSellValue = 0.0;
        boolean allPricesLoaded = true;

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
          if (priceData == null) {
            allPricesLoaded = false;
            continue;
          }
          if (!priceData.isItemNotFound()) {
            if (priceData.getBuyPrice() != null) {
              totalBuyValue += priceData.getBuyPrice() * stackSize;
            }
            if (priceData.getSellPrice() != null) {
              totalSellValue += priceData.getSellPrice() * stackSize;
            }
          }
        }
        currentInventoryValue.set(new InventoryValueData(totalItems, totalBuyValue, totalSellValue, allPricesLoaded));
        needsDisplayUpdate = true;
      } catch (Throwable t) {
        currentInventoryValue.set(new InventoryValueData(0, 0.0, 0.0, true));
        needsDisplayUpdate = true;
      } finally {
        isCalculating = false;
      }
    });
  }

  private PriceData getPriceDataForItem(String itemId, JsonObject market) {
    CachedPriceData cached = priceCache.get(itemId);
    if (cached != null && System.currentTimeMillis() - cached.timestamp < PRICE_CACHE_DURATION_MS) {
      return cached.priceData;
    }

    long now = System.currentTimeMillis();
    if (market == null || now - marketCacheTimestamp > MARKET_CACHE_MS) {
      if (!pendingMarketRefresh) {
        pendingMarketRefresh = true;
        executor.submit(() -> {
          try {
            loadMarketJsonFromAPI();
          } finally {
            pendingMarketRefresh = false;
          }
        });
      }
      return null;
    }

    try {
      PriceData pd = findItemInCategories(market, itemId.toUpperCase(Locale.ROOT));
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

  private void loadMarketJsonFromAPI() {
    try {
      String apiUrl = "https://api.opsucht.net/market/prices";
      String resp = httpGet(apiUrl);
      if (resp != null && !resp.isEmpty()) {
        JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
        marketJsonCache.set(json);
        marketCacheTimestamp = System.currentTimeMillis();
        needsDisplayUpdate = true;
      }
    } catch (Exception ex) {
    }
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

  private String httpGet(String urlStr) {
    HttpURLConnection conn = null;
    try {
      URI uri = URI.create(urlStr);
      conn = (HttpURLConnection) uri.toURL().openConnection();
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
      lastDisplayedKey.set(null);
      pendingMarketRefresh = false;
      isCalculating = false;
      marketDataLoaded = false;
      needsDisplayUpdate = false;
    }
  }

  private static class InventoryValueData {
    final int totalItems;
    final double totalBuyValue;
    final double totalSellValue;
    final boolean calculationComplete;
    InventoryValueData(int totalItems, double totalBuyValue, double totalSellValue, boolean calculationComplete) {
      this.totalItems = totalItems;
      this.totalBuyValue = totalBuyValue;
      this.totalSellValue = totalSellValue;
      this.calculationComplete = calculationComplete;
    }
    @Override
    public int hashCode() {
      return Objects.hash(totalItems, totalBuyValue, totalSellValue, calculationComplete);
    }
  }

  private static class PriceData {
    final boolean itemNotFound;
    final Double buy;
    final Double sell;
    PriceData(boolean itemNotFound, Double buy, Double sell) {
      this.itemNotFound = itemNotFound;
      this.buy = buy;
      this.sell = sell;
    }
    boolean isItemNotFound() { return itemNotFound; }
    Double getBuyPrice() { return buy; }
    Double getSellPrice() { return sell; }
  }

  private static class CachedPriceData {
    final PriceData priceData;
    final long timestamp;
    CachedPriceData(PriceData p, long ts) {
      this.priceData = p;
      this.timestamp = ts;
    }
  }
}