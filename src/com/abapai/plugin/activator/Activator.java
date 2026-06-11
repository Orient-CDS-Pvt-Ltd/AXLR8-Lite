// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.activator;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * AXLR8 Lite Activator.
 *
 * <p>Minimal OSGi BundleActivator + AbstractUIPlugin singleton, used by the
 * service layer ({@code ABAPAIPreferences}, etc.) to look up the bundle's
 * preference store and the plugin ID.
 *
 * <p>The {@code Bundle-SymbolicName} in MANIFEST.MF (com.orient.axlr8lite)
 * gives the bundle its own classloader and preference scope.
 */
public class Activator extends AbstractUIPlugin {

    /** Bundle symbolic name. MUST match Bundle-SymbolicName in MANIFEST.MF. */
    public static final String PLUGIN_ID = "com.orient.axlr8lite";

    private static Activator plugin;

    public Activator() {}

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    /** Singleton accessor used by the preference + service layer. */
    public static Activator getDefault() { return plugin; }

    /** Preference store for this bundle's settings. */
    @Override
    public IPreferenceStore getPreferenceStore() {
        return super.getPreferenceStore();
    }
}
