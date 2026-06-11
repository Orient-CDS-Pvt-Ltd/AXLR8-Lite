// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.services.backend;

/**
 * The SAP target the user is writing ABAP for.
 *
 * <p>Persisted via {@link com.abapai.plugin.preferences.ABAPAIPreferences#KEY_EHP8_MODE}.
 * Read/written through {@link BackendModeService}.
 *
 * <p>S4HANA (default) — modern ABAP Platform. RAP, CDS view entities, and
 *    7.54+ language features are available; the AI may suggest them freely.
 *
 * <p>EHP8 — classic SAP ECC 6.0 EHP8 (SAP_BASIS 7.50/7.52). The AI avoids
 *    RAP, CDS view entities, utclong, and other 7.54+ features and sticks to
 *    classic ABAP constructs.
 */
public enum BackendMode {
    S4HANA,
    EHP8
}
