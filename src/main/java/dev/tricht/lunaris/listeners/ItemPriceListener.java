package dev.tricht.lunaris.listeners;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tricht.lunaris.WindowsAPI;
import dev.tricht.lunaris.com.pathofexile.NotYetImplementedException;
import dev.tricht.lunaris.com.pathofexile.PathOfExileAPI;
import dev.tricht.lunaris.com.pathofexile.RateLimitMostLikelyException;
import dev.tricht.lunaris.com.pathofexile.response.ListingResponse;
import dev.tricht.lunaris.com.pathofexile.response.SearchResponse;
import dev.tricht.lunaris.elements.Label;
import dev.tricht.lunaris.item.Item;
import dev.tricht.lunaris.item.ItemGrabber;
import dev.tricht.lunaris.tooltip.TooltipCreator;
import dev.tricht.lunaris.elements.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.jnativehook.NativeInputEvent;
import org.jnativehook.keyboard.NativeKeyEvent;
import org.jnativehook.keyboard.NativeKeyListener;
import org.jnativehook.mouse.NativeMouseEvent;
import org.jnativehook.mouse.NativeMouseInputListener;
import org.ocpsoft.prettytime.PrettyTime;

import java.awt.*;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class ItemPriceListener implements NativeKeyListener, NativeMouseInputListener {

    private ItemGrabber itemGrabber;
    private Point position;
    private PathOfExileAPI pathOfExileAPI;
    private PrettyTime prettyTime;
    private ObjectMapper objectMapper;
    private SearchResponse currentSearch = null;

    public ItemPriceListener(ItemGrabber itemGrabber, PathOfExileAPI pathOfExileAPI) {
        this.itemGrabber = itemGrabber;
        this.pathOfExileAPI = pathOfExileAPI;
        this.prettyTime = new PrettyTime();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent event) {
        try {
            if (!WindowsAPI.isPoeActive()) {
                return;
            }
            if (event.getKeyCode() == NativeKeyEvent.VC_D && event.getModifiers() == NativeInputEvent.ALT_L_MASK) {
                VoidDispatchService.consume(event);
                displayItemTooltip();
            }

            if (event.getKeyCode() == NativeKeyEvent.VC_Q && event.getModifiers() == NativeInputEvent.ALT_L_MASK) {
                VoidDispatchService.consume(event);
                if (currentSearch != null) {
                    WindowsAPI.browse(currentSearch.getUrl(pathOfExileAPI.getLeague()));
                    return;
                }
                log.debug("pathofexile.com/trade");
                Item item = this.itemGrabber.grab();
                if (item == null || !item.hasPrice()) {
                    log.debug("No item selected.");
                    return;
                }
                log.debug("Got item, translating to pathofexile.com");
                this.pathOfExileAPI.find(item, new Callback() {
                    @Override
                    public void onFailure(@NotNull Call call, @NotNull IOException e) {
                        log.debug("Failed to search", e);
                    }

                    @Override
                    public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                        try {
                            SearchResponse searchResponse = objectMapper.readValue(response.body().string(), SearchResponse.class);
                            if (searchResponse != null && searchResponse.getId() != null) {
                                WindowsAPI.browse(searchResponse.getUrl(pathOfExileAPI.getLeague()));
                            }
                            log.debug(searchResponse.toString());
                        } catch (IOException e) {
                            log.debug("Failed to parse response", e);
                        }
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void displayItemTooltip() {
        Item item = this.itemGrabber.grab();
        if (item == null || !item.hasPrice()) {
            return;
        }

        Map<Element, int[]> elements = createBaseItemTooltip(item);
        elements.put(new Label("Loading from pathofexile.com..."), new int[]{1, elements.size() - 1});
        addPoeNinjaPrice(item, elements);
        TooltipCreator.create(position, elements);

        try {
            this.pathOfExileAPI.find(item, new TradeSearchCallback(item));
        } catch (NotYetImplementedException e) {
            log.error("Item not yet implemented", e);
            displayError(item, "This item has not been implemented yet");
        }
    }

    @NotNull
    private Map<Element, int[]> createBaseItemTooltip(Item item) {
        Map<Element, int[]> elements;
        elements = new LinkedHashMap<>();
        elements.put(new Icon(item, 48), new int[]{0, 0});
        elements.put(new ItemName(item, 48 + Icon.PADDING), new int[]{1, 0});
        return elements;
    }

    private void addPoeNinjaPrice(Item item, Map<Element, int[]> elements) {
        elements.put(new Price(item), new int[]{1, elements.size() - 1});
        if (item.getMeanPrice().getReason() != null) {
            elements.put(new Label("Reason: " + item.getMeanPrice().getReason()), new int[]{1, elements.size() - 1});
        }
        elements.put(new Source("poe.ninja"), new int[]{1, elements.size() - 1});
    }

    class TradeSearchCallback implements Callback {

        private Item item;

        public TradeSearchCallback(Item item) {
            this.item = item;
        }

        @Override
        public void onFailure(@NotNull Call call, @NotNull IOException e) {
            displayError(item, "Failed to load from pathofexile.com");
        }

        @Override
        public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
            try {
                SearchResponse searchResponse = objectMapper.readValue(response.body().string(), SearchResponse.class);
                if (searchResponse != null && searchResponse.getId() != null && !searchResponse.getResult().isEmpty()) {
                    java.util.List<ListingResponse.Item> items = null;
                    try {
                        items = pathOfExileAPI.getItemListings(searchResponse);
                    } catch (RateLimitMostLikelyException e) {
                        log.debug("Error while getting item listing", e);
                    }
                    if (items != null) {
                        Map<Element, int[]> elements = createBaseItemTooltip(item);
                        StringBuilder text = new StringBuilder();
                        for (ListingResponse.Item listingItem : items) {
                            text.append(String.format("%s sold by %s since %s",
                                    listingItem.getListing().getPrice(),
                                    listingItem.getListing().getAccount().getLastCharacterName(),
                                    prettyTime.format(listingItem.getListing().getTimeAgo())
                            )).append("\n");
                        }
                        elements.put(new Label(text.toString()), new int[]{1, elements.size() - 1});
                        elements.put(new Label("Press alt + q to open in your browser"), new int[]{1, elements.size() - 1});
                        elements.put(new Source("pathofexile.com"), new int[]{1, elements.size() - 1});
                        addPoeNinjaPrice(item, elements);
                        ItemPriceListener.this.currentSearch = searchResponse;
                        TooltipCreator.create(position, elements);
                    }
                } else {
                    displayError(item, "pathofexile.com gave no results");
                }
            } catch (IOException e) {
                displayError(item, "Too many requests to pathofexile.com\nPlease wait a few seconds");
            }
        }
    }

    private void displayError(Item item, String errorMessage) {
        Map<Element, int[]> elements = createBaseItemTooltip(item);
        elements.put(new Label(errorMessage), new int[]{1, elements.size() - 1});
        addPoeNinjaPrice(item, elements);
        TooltipCreator.create(position, elements);
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent event) {
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent event) {
    }

    @Override
    public void nativeMouseMoved(NativeMouseEvent event) {
        position = event.getPoint();
    }

    @Override
    public void nativeMouseDragged(NativeMouseEvent event) {
    }

    @Override
    public void nativeMouseClicked(NativeMouseEvent event) {
    }

    @Override
    public void nativeMousePressed(NativeMouseEvent event) {
        currentSearch = null;
        TooltipCreator.hide();
    }

    @Override
    public void nativeMouseReleased(NativeMouseEvent event) {
    }
}
