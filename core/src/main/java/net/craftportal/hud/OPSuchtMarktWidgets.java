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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static net.labymod.api.Laby.labyAPI;

public class OPSuchtMarktWidgets extends TextHudWidget<TextHudWidgetConfig> {

  private final AtomicReference<ItemData> currentItem = new AtomicReference<>(null);
  private final AtomicReference<String> lastDisplayedKey = new AtomicReference<>(null);

  private TextLine nameLine;
  private long lastAutoDetect = 0L;
  private static final long AUTO_DETECT_INTERVAL_MS = 200;

  private ScheduledExecutorService executor = null;
  private final Object executorLock = new Object();

  private final ConcurrentHashMap<String, CachedPriceData> priceCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Boolean> pendingRequests = new ConcurrentHashMap<>();
  private static final long PRICE_CACHE_DURATION_MS = 30000;
  private static final long CACHE_CLEANUP_INTERVAL_MS = 300000;

  private final OPSuchtMarktConfig config;

  private final Component loadingComponent;
  private final Component noItemComponent;
  private final Component noPriceComponent;
  private final Component unknownItemComponent;

  private TextColor buyColorCache;
  private TextColor sellColorCache;
  private int lastBuyColor = -1;
  private int lastSellColor = -1;
  private net.labymod.api.util.Color lastConfigBuyColor;
  private net.labymod.api.util.Color lastConfigSellColor;

  private static final TextColor SEPARATOR_COLOR = TextColor.color(170, 170, 170);

  private final NumberFormat numberFormat = NumberFormat.getNumberInstance(Locale.GERMAN);

  private static final String CURRENCY_SYMBOL = "$";

  private final ConcurrentHashMap<String, Component> itemNameCache = new ConcurrentHashMap<>();

  private boolean isEditorMode = false;

  private volatile boolean needsDisplayUpdate = false;

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

    boolean buyColorChanged = currentBuyColor.getRed() != lastConfigBuyColor.getRed() ||
        currentBuyColor.getGreen() != lastConfigBuyColor.getGreen() ||
        currentBuyColor.getBlue() != lastConfigBuyColor.getBlue();

    boolean sellColorChanged = currentSellColor.getRed() != lastConfigSellColor.getRed() ||
        currentSellColor.getGreen() != lastConfigSellColor.getGreen() ||
        currentSellColor.getBlue() != lastConfigSellColor.getBlue();

    if (buyColorChanged || sellColorChanged) {
      this.lastConfigBuyColor = currentBuyColor;
      this.lastConfigSellColor = currentSellColor;

      this.buyColorCache = TextColor.color(currentBuyColor.getRed(), currentBuyColor.getGreen(), currentBuyColor.getBlue());
      this.sellColorCache = TextColor.color(currentSellColor.getRed(), currentSellColor.getGreen(), currentSellColor.getBlue());

      this.lastBuyColor = currentBuyColor.getRed() << 16 | currentBuyColor.getGreen() << 8 | currentBuyColor.getBlue();
      this.lastSellColor = currentSellColor.getRed() << 16 | currentSellColor.getGreen() << 8 | currentSellColor.getBlue();
    }
  }

  @Override
  public void onTick(boolean isEditorContext) {
    updateColorCacheIfNeeded();

    if (isEditorContext) {
      isEditorMode = true;
      this.nameLine.updateAndFlush(this.loadingComponent);
      this.nameLine.setState(State.VISIBLE);
      return;
    }

    isEditorMode = false;

    if (this.config == null || !this.config.enabled().get()) {
      this.nameLine.setState(State.HIDDEN);
      stopExecutorIfRunning();
      return;
    }

    ensureExecutorRunning();

    long now = System.currentTimeMillis();
    if (now - lastAutoDetect > AUTO_DETECT_INTERVAL_MS) {
      lastAutoDetect = now;
      tryAutoDetectHeldItem();
    }

    updateDisplay();
  }

  private void updateDisplay() {
    ItemData itemData = currentItem.get();

    String itemId = itemData != null ? itemData.id : null;
    int stackSize = itemData != null ? itemData.stackSize : 0;

    DisplayMode currentDisplayMode = this.config.displayMode().get();
    boolean currentShowItemName = this.config.showItemName().get();
    boolean currentUseStackSize = this.config.useStackSize().get();

    String key = (itemId == null ? "null" : itemId) + ":" + stackSize + ":" + currentDisplayMode.name() + ":" + currentShowItemName + ":" + currentUseStackSize;
    String previousKey = lastDisplayedKey.get();

    if (Objects.equals(key, previousKey) && !needsDisplayUpdate) {
      return;
    }

    Component displayText = buildDisplayText(itemData, currentDisplayMode, currentShowItemName, currentUseStackSize);
    this.nameLine.updateAndFlush(displayText);
    this.nameLine.setState(State.VISIBLE);

    lastDisplayedKey.set(key);
    needsDisplayUpdate = false;
  }

  private Component buildDisplayText(ItemData itemData, DisplayMode displayMode, boolean showItemName, boolean useStackSize) {
    if (itemData == null || itemData.id == null || itemData.id.isEmpty() || "unknown".equals(itemData.id)) {
      return this.noItemComponent;
    }

    Component displayNameComp = formatItemIdComponent(itemData.id);

    CachedPriceData cached = priceCache.get(itemData.id);
    if (cached != null && System.currentTimeMillis() - cached.timestamp < PRICE_CACHE_DURATION_MS) {
      PriceData priceData = cached.priceData;
      if (priceData.isItemNotFound()) {
        return Component.translatable("opsuchtmarkt.prices.noPrice");
      } else {
        Component priceText = createPriceText(priceData.getBuyPrice(), priceData.getSellPrice(),
            itemData.stackSize, useStackSize);
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

  private Component createPriceText(Double buyPrice, Double sellPrice, int stackSize, boolean useStackSize) {
    DisplayMode mode = this.config.displayMode().get();
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
    try {
      if (labyAPI().minecraft() == null || labyAPI().minecraft().clientPlayer() == null) {
        currentItem.set(null);
        return;
      }
      ItemStack stk = labyAPI().minecraft().clientPlayer().getMainHandItemStack();
      if (stk == null || stk.isAir()) {
        currentItem.set(null);
        return;
      }

      String id = getItemIdFromIdentifier(stk);
      int size = getStackSize(stk);

      if (id != null && !id.isEmpty() && !"unknown".equals(id)) {
        ItemData newData = new ItemData(id, size);
        ItemData cur = currentItem.get();
        if (cur == null || !cur.equals(newData)) {
          currentItem.set(newData);
          loadPriceData(id);
        }
      } else {
        currentItem.set(null);
      }
    } catch (Throwable t) {
      currentItem.set(null);
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
    executor.submit(() -> {
      try {
        String apiUrl = "https://api.opsucht.net/market/price/" + itemId.toUpperCase(Locale.ROOT);
        String resp = httpGet(apiUrl);
        if (resp != null && !resp.isEmpty()) {
          JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
          JsonArray arr = json.getAsJsonArray(itemId.toUpperCase(Locale.ROOT));
          if (arr == null || arr.size() == 0) {
            PriceData pd = new PriceData(true, null, null);
            priceCache.put(itemId, new CachedPriceData(pd, System.currentTimeMillis()));
          } else {
            Double buy = null, sell = null;
            for (JsonElement e : arr) {
              JsonObject o = e.getAsJsonObject();
              String side = o.get("orderSide").getAsString();
              double p = o.get("price").getAsDouble();
              if ("BUY".equals(side)) buy = p;
              else if ("SELL".equals(side)) sell = p;
            }
            PriceData pd = new PriceData(false, buy, sell);
            priceCache.put(itemId, new CachedPriceData(pd, System.currentTimeMillis()));
          }
          needsDisplayUpdate = true;
        } else {
          PriceData pd = new PriceData(false, null, null);
          priceCache.put(itemId, new CachedPriceData(pd, System.currentTimeMillis()));
          needsDisplayUpdate = true;
        }
      } catch (Exception ex) {
        PriceData pd = new PriceData(false, null, null);
        priceCache.put(itemId, new CachedPriceData(pd, System.currentTimeMillis()));
        needsDisplayUpdate = true;
      } finally {
        pendingRequests.remove(itemId);
      }
    });
  }

  private String httpGet(String urlStr) {
    HttpURLConnection conn = null;
    try {
      URL url = new URL(urlStr);
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(3000);
      conn.setReadTimeout(3000);
      conn.setRequestProperty("User-Agent", "LabyMod-OPSuchtMarkt/1.0");
      int code = conn.getResponseCode();
      if (code == 200) {
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
      } else if (code == 404) {
        return "{}";
      }
    } catch (Exception e) {
    } finally {
      if (conn != null) conn.disconnect();
    }
    return null;
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
      lastDisplayedKey.set(null);
    }
  }

  public void shutdown() {
    stopExecutorIfRunning();
  }

  private static class ItemData {
    final String id;
    final int stackSize;
    ItemData(String id, int stackSize) {
      this.id = id;
      this.stackSize = stackSize;
    }
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ItemData other = (ItemData) o;
      return stackSize == other.stackSize && Objects.equals(id, other.id);
    }
    @Override
    public int hashCode() {
      return Objects.hash(id, stackSize);
    }
  }

  private static class PriceData {
    final boolean itemNotFound;
    final Double buy, sell;
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