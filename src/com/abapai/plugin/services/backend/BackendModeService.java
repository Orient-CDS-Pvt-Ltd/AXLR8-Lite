// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.services.backend;

import com.abapai.plugin.preferences.ABAPAIPreferences;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Singleton holding the current {@link BackendMode}.
 *
 * <p>Single source of truth for the user's SAP target. Reads/writes the
 * preference {@link ABAPAIPreferences#KEY_EHP8_MODE}; fires listener
 * callbacks on change so the UI (mode dropdown, status labels) stays in sync.
 *
 * <p>Usage:
 * <pre>
 *   if (BackendModeService.getInstance().getMode() == BackendMode.EHP8) {
 *       // tailor the AI prompt for classic ABAP
 *   }
 * </pre>
 *
 * <p>This service is thread-safe. Listener fan-out uses a
 * {@link CopyOnWriteArrayList} so listeners can register/unregister
 * during dispatch without ConcurrentModificationException.
 */
public final class BackendModeService {

    private static final Logger LOG = Logger.getLogger(BackendModeService.class.getName());
    private static final BackendModeService INSTANCE = new BackendModeService();

    private final CopyOnWriteArrayList<Consumer<BackendMode>> listeners = new CopyOnWriteArrayList<>();

    private BackendModeService() {}

    public static BackendModeService getInstance() {
        return INSTANCE;
    }

    /**
     * @return current backend mode. {@code S4HANA} by default; switches to
     *         {@code EHP8} when the user flips the toolbar toggle.
     */
    public BackendMode getMode() {
        return ABAPAIPreferences.isEhp8Mode() ? BackendMode.EHP8 : BackendMode.S4HANA;
    }

    /**
     * Convenience: true iff current mode is EHP8.
     */
    public boolean isEhp8Mode() {
        return getMode() == BackendMode.EHP8;
    }

    /**
     * Set the mode (persists to preferences) and fire listener callbacks.
     * Idempotent: if the new mode equals the current mode, no listeners fire.
     */
    public void setMode(BackendMode newMode) {
        if (newMode == null) return;
        BackendMode currentMode = getMode();
        if (currentMode == newMode) return;

        boolean ehp8 = (newMode == BackendMode.EHP8);
        ABAPAIPreferences.setEhp8Mode(ehp8);
        LOG.info("BackendModeService: mode changed " + currentMode + " -> " + newMode);

        for (Consumer<BackendMode> listener : listeners) {
            try {
                listener.accept(newMode);
            } catch (Throwable t) {
                LOG.warning("BackendModeService listener failed: " + t.getMessage());
            }
        }
    }

    /**
     * Subscribe to mode changes. Listener is invoked AFTER persistence
     * with the new mode. Idempotent registration.
     */
    public void addListener(Consumer<BackendMode> listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /** Unsubscribe. Safe to call from inside the listener. */
    public void removeListener(Consumer<BackendMode> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }
}
