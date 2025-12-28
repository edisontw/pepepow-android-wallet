package de.schildbach.wallet.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central event bus for overlay state changes.
 * Emits events that trigger UI refresh without requiring navigation.
 * Objective B: Event-driven immediate UI updates.
 */
public final class OverlayEventBus {
    private static final Logger log = LoggerFactory.getLogger(OverlayEventBus.class);

    public enum EventType {
        SESSION_WALLET_READY,
        SNAPSHOT_UPDATED,
        JOURNAL_APPLIED,
        TX_INCOMING_ADDED,
        TX_OUTGOING_BROADCASTED,
        BALANCE_CHANGED,
        HISTORY_CHANGED,
        DATA_SOURCE_SWITCHED
    }

    private static String sessionId = "UNKNOWN";
    private static EventListener listener;

    public interface EventListener {
        void onOverlayEvent(EventType type, String reason);
    }

    public static void setSessionId(String sid) {
        sessionId = sid != null ? sid : "UNKNOWN";
    }

    public static String getSessionId() {
        return sessionId;
    }

    public static void setListener(EventListener l) {
        listener = l;
    }

    /**
     * Emit an overlay event with required logging.
     * 
     * @param type    Event type
     * @param reason  Reason for the event
     * @param balance Current balance string
     * @param pending Current pending string
     * @param source  Data source string
     */
    public static void emit(EventType type, String reason, String balance, String pending, String source) {
        log.info("OVERLAY_EVENT emit type={} reason={} balance={} pending={} src={}",
                type, reason, balance, pending, source);

        if (listener != null) {
            listener.onOverlayEvent(type, reason);
        }
    }

    /**
     * Shorthand for emitting without all parameters.
     */
    public static void emit(EventType type, String reason) {
        emit(type, reason, "?", "?", "?");
    }
}
