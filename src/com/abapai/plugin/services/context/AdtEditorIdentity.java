// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.services.context;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.Adapters;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.MultiPageEditorPart;

/**
 * Resolves the active Eclipse editor's ADT object identity (object name,
 * ADT type code, ADT URI) -- when the editor is bound to a remote SAP object
 * via the SAP ADT plugin.
 *
 * <p>Why this exists: {@code resolveActiveWorkspaceFile()} in
 * {@link com.abapai.plugin.views.chat.ABAPChatView} returns {@code null} for
 * ADT editors because ADT files (e.g. {@code ztravelreqapp.asprog}) are NOT
 * backed by a workspace {@code IFile} -- they are virtual editors over remote
 * SAP objects. As a result, mode 12 (ATC) and mode 2 (Apply to SAP) couldn't
 * identify the active object and fell back to AI-simulated reviews even when
 * real ATC was available on the SAP backend.
 *
 * <p>Resolution strategy (first hit wins):
 * <ol>
 *   <li><b>Reflective ADT adapter probe</b> -- ask the active editor (and its
 *       editor input) for {@code com.sap.adt.tools.core.model.adtcore.IAdtObjectReference}.
 *       This is the canonical SAP-blessed surface that yields name + type + URI
 *       directly from the ADT runtime. Reflection so this plugin doesn't gain
 *       a hard compile-time dependency on the SAP ADT JARs.</li>
 *   <li><b>Filename + extension fallback</b> -- use
 *       {@link FileContextService#getCurrentEditorLocation()} to retrieve the
 *       editor's filename (which works even for ADT editors via the project +
 *       filename synthesis path). Map the well-known ADT extensions
 *       ({@code .asprog}, {@code .asclass}, ...) to ADT type codes.</li>
 * </ol>
 *
 * <p>If both paths fail, returns {@link Optional#empty()} -- caller should
 * treat that as "no active object identified" and fall back to AI-only mode.
 *
 * <p>Safety: every reflective call is wrapped in try/catch. The class
 * compiles and runs cleanly on Eclipse instances where the SAP ADT plugin
 * is NOT installed (the reflective path simply returns null).
 */
public final class AdtEditorIdentity {

    /**
     * Identity of an ADT-bound object.
     *
     * @param name  SAP object name (e.g. {@code ZTRAVELREQAPP}). Always
     *              upper-case when returned from this class.
     * @param type  ADT type code (e.g. {@code PROG/P}, {@code CLAS/OC},
     *              {@code BDEF/BDO}). Empty string if the type couldn't be
     *              determined -- caller can fall back to keyword scan.
     * @param uri   ADT URI of the object (e.g.
     *              {@code /sap/bc/adt/programs/programs/ztravelreqapp}).
     *              Empty string if not available -- caller can compute
     *              from name+type via {@code computeAdtUriFromTypeAndName}.
     */
    public record AdtObjectIdentity(String name, String type, String uri, String packageName) {
        // Backward-compatible 3-arg ctor for any caller still using the old shape.
        public AdtObjectIdentity(String name, String type, String uri) {
            this(name, type, uri, "");
        }
        public boolean hasPackageName() {
            return packageName != null && !packageName.isBlank();
        }
        public boolean hasName() { return name != null && !name.isBlank(); }
        public boolean hasUri()  { return uri  != null && !uri.isBlank(); }
    }

    private static final String IADT_OBJECT_REFERENCE_FQN =
            "com.sap.adt.tools.core.model.adtcore.IAdtObjectReference";

    /**
     * Diagnostic of the last {@link #tryResolve()} call. Useful for
     * surfacing in the chat fallback message when resolution fails --
     * e.g. "no editor open", "ADT class not visible to bundle", etc.
     */
    private static volatile String lastDiagnostic = "(not yet called)";

    private AdtEditorIdentity() {}

    /** Last resolution diagnostic -- safe to call from any thread. */
    public static String lastDiagnostic() { return lastDiagnostic; }

    /**
     * Resolve the active editor's ADT object identity.
     *
     * <p><b>MUST be called on the SWT UI thread.</b> The implementation
     * touches PlatformUI / IWorkbenchWindow / IEditorPart, all of which
     * return null (or undefined values) off the UI thread. The canonical
     * pattern is to call this from the chat-submit handler (UI thread)
     * BEFORE dispatching to a CompletableFuture worker, then pass the
     * resulting {@code Optional<AdtObjectIdentity>} into the worker as a
     * parameter.
     *
     * <p>If accidentally called from a non-UI thread, the result will
     * almost always be {@link Optional#empty()} with a
     * "no active editor" diagnostic. Use {@link #tryResolveSafe()}
     * for defensive worker-thread calls.
     *
     * <p>Never throws. Diagnostic available via {@link #lastDiagnostic()}.
     */
    public static Optional<AdtObjectIdentity> tryResolveOnUiThreadOnly() {
        // ── Path 1: ADT adapter probe via Adapters.adapt() ───────────────
        StringBuilder diag = new StringBuilder();
        AdtObjectIdentity viaAdt = tryResolveViaAdtAdapter(diag);
        if (viaAdt != null) {
            lastDiagnostic = "resolved via ADT adapter: " + viaAdt.name()
                    + " (type=" + viaAdt.type() + ", uri=" + viaAdt.uri() + ")";
            return Optional.of(viaAdt);
        }

        // ── Path 2: filename + extension fallback ────────────────────────
        AdtObjectIdentity viaFile = tryResolveViaFilename(diag);
        if (viaFile != null) {
            lastDiagnostic = "resolved via filename fallback: " + viaFile.name()
                    + " (type=" + (viaFile.type().isBlank() ? "?" : viaFile.type()) + ")";
            return Optional.of(viaFile);
        }

        lastDiagnostic = "resolution failed; " + diag;
        return Optional.empty();
    }

    /**
     * Defensive wrapper around {@link #tryResolveOnUiThreadOnly()}: if
     * the caller is already on the UI thread, runs directly; if on a
     * worker thread, bridges via {@code Display.syncExec}.
     *
     * <p><b>Prefer the capture-once-on-UI-thread pattern</b> (call
     * {@link #tryResolveOnUiThreadOnly()} in the submit handler and pass
     * the result into the worker) over this method. This wrapper exists
     * only as defensive code for callers that can't easily restructure
     * to do the up-front capture, AND it has weaker correctness:
     * the user may have changed editors between submit and the bridge
     * firing, so the resolved object may differ from what the user saw
     * when they pressed Submit.
     */
    public static Optional<AdtObjectIdentity> tryResolveSafe() {
        try {
            Display display = PlatformUI.getWorkbench().getDisplay();
            if (Display.getCurrent() == display) {
                return tryResolveOnUiThreadOnly();
            }
            AtomicReference<Optional<AdtObjectIdentity>> ref =
                    new AtomicReference<>(Optional.empty());
            display.syncExec(() -> ref.set(tryResolveOnUiThreadOnly()));
            return ref.get();
        } catch (Throwable t) {
            lastDiagnostic = "tryResolveSafe: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage();
            return Optional.empty();
        }
    }

    /** @deprecated Use {@link #tryResolveOnUiThreadOnly()} (UI thread)
     *  or {@link #tryResolveSafe()} (worker, defensive). */
    @Deprecated
    public static Optional<AdtObjectIdentity> tryResolve() {
        return tryResolveOnUiThreadOnly();
    }

    /**
     * Public probe: resolve the ADT identity of a SPECIFIC editor, not just
     * the active one. Lets callers walk all open workbench editors and match
     * each one against a target artifact.
     *
     * <p>Must be called from the UI thread (touches workbench/editor state).
     *
     * @param editor the editor to probe; {@code null} returns {@code Optional.empty()}
     * @return ADT identity for that editor's input, or empty if the editor
     *         is not an ADT editor / has no IAdtObjectReference adapter
     */
    public static Optional<AdtObjectIdentity> probeEditor(IEditorPart editor) {
        if (editor == null) return Optional.empty();
        StringBuilder diag = new StringBuilder();
        AdtObjectIdentity id = probeEditorViaAdtAdapter(editor, diag);
        return Optional.ofNullable(id);
    }

    private static AdtObjectIdentity tryResolveViaAdtAdapter(StringBuilder diag) {
        IEditorPart editor = activeEditor();
        if (editor == null) { diag.append("Path1: no active editor; "); return null; }
        return probeEditorViaAdtAdapter(editor, diag);
    }

    /**
     * Reflective probe against a specific editor — the load-bearing logic
     * shared by {@link #tryResolveViaAdtAdapter(StringBuilder)} (active
     * editor) and {@link #probeEditor(IEditorPart)} (any editor).
     */
    private static AdtObjectIdentity probeEditorViaAdtAdapter(IEditorPart editor, StringBuilder diag) {
        try {
            if (editor == null) { diag.append("Path1: editor is null; "); return null; }

            // OSGi classloader gotcha: in Eclipse, each plugin bundle has its
            // own classloader. Our bundle does NOT declare a Require-Bundle
            // dependency on com.sap.adt.tools.core.base, so a plain
            // Class.forName(IADT_OBJECT_REFERENCE_FQN) from THIS class's
            // classloader will throw ClassNotFoundException -- the SAP ADT
            // bundle's exported types are invisible to us.
            //
            // The editor object IS loaded by the SAP ADT bundle (or one that
            // depends on it), so its classloader CAN see IAdtObjectReference.
            // Use it to resolve the interface class.
            Class<?> ifc;
            try {
                ifc = Class.forName(IADT_OBJECT_REFERENCE_FQN, false,
                                    editor.getClass().getClassLoader());
            } catch (ClassNotFoundException notInstalled) {
                diag.append("Path1: IAdtObjectReference not visible from "
                            + editor.getClass().getName() + " classloader; ");
                return null;
            }

            // Use Eclipse's canonical Adapters.adapt() rather than direct
            // getAdapter(): it handles IAdaptable + adapter manager +
            // assignability in one call, and is the recommended adapter
            // pattern per the Eclipse platform Javadoc.
            Object ref = Adapters.adapt(editor, ifc);
            if (ref == null) {
                IEditorInput input = editor.getEditorInput();
                if (input != null) {
                    ref = Adapters.adapt(input, ifc);
                }
            }
            // For multi-page ADT editors (BlueAdtFormEditor and subclasses),
            // try the selected inner page if outer adaptation failed.
            // MultiPageEditorPart.getAdapter() is supposed to delegate, but
            // SAP subclasses may override -- defensive layer.
            if (ref == null && editor instanceof MultiPageEditorPart mpe) {
                Object selected = mpe.getSelectedPage();
                if (selected != null) {
                    ref = Adapters.adapt(selected, ifc);
                }
            }
            if (ref == null) {
                diag.append("Path1: editor+input+selectedPage all returned null for Adapters.adapt("
                            + ifc.getSimpleName() + "); ");
                return null;
            }

            String name = invokeStringGetter(ifc, ref, "getName");
            String type = invokeStringGetter(ifc, ref, "getType");
            String uri  = invokeStringGetter(ifc, ref, "getUri");
            // ADT's IAdtObjectReference exposes getPackageName() — the artifact's
            // owning package, captured by ADT when the editor was opened. This
            // is what Project Explorer uses to show the artifact under the right
            // package node. Reading it directly avoids a SAP HTTP roundtrip
            // (which can fail with "anonymous session" / 415 / AMBIGUOUS).
            String packageName = invokeStringGetter(ifc, ref, "getPackageName");

            if (name == null || name.isBlank()) {
                diag.append("Path1: IAdtObjectReference.getName() returned blank; ");
                return null;
            }

            return new AdtObjectIdentity(
                    name.toUpperCase(Locale.ROOT),
                    type == null ? "" : type,
                    uri  == null ? "" : uri,
                    packageName == null ? "" : packageName.toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            diag.append("Path1: exception " + t.getClass().getSimpleName() + ": " + t.getMessage() + "; ");
            return null;
        }
    }

    /**
     * Filename-based fallback: extract the active editor's filename via
     * FileContextService (which already handles ADT editors), then map the
     * well-known ADT extensions to type codes.
     *
     * <p>Sample mappings:
     * <pre>
     *   ztravelreqapp.asprog   -> ZTRAVELREQAPP, PROG/P
     *   zcl_travel_req_app.asclass -> ZCL_TRAVEL_REQ_APP, CLAS/OC
     *   zif_foo.asintf         -> ZIF_FOO, INTF/OI
     *   zbdef_travel.asbdef    -> ZBDEF_TRAVEL, BDEF/BDO
     *   zddls_travel.asddls    -> ZDDLS_TRAVEL, DDLS/DF
     * </pre>
     */
    private static AdtObjectIdentity tryResolveViaFilename(StringBuilder diag) {
        try {
            FileContextService.EditorLocation loc =
                    FileContextService.getInstance().getCurrentEditorLocation();
            String fname = loc.fileName();
            if (fname == null || fname.isBlank()) {
                diag.append("Path2: FileContextService.getCurrentEditorLocation() returned blank fileName; ");
                return null;
            }

            int dot = fname.lastIndexOf('.');
            String stem = dot > 0 ? fname.substring(0, dot) : fname;
            String ext  = dot > 0 ? fname.substring(dot + 1).toLowerCase(Locale.ROOT) : "";

            String type = adtExtensionToType(ext);
            if (type.isBlank() && !ext.isBlank()) {
                diag.append("Path2: extension '" + ext + "' not in mapping table; ");
            }
            return new AdtObjectIdentity(
                    stem.toUpperCase(Locale.ROOT),
                    type,
                    "");   // no URI from filename alone -- caller computes
        } catch (Throwable t) {
            diag.append("Path2: exception " + t.getClass().getSimpleName() + ": " + t.getMessage() + "; ");
            return null;
        }
    }

    /**
     * ADT file-extension to ADT type code mapping. Both naming conventions
     * are accepted: {@code .a<type>} (e.g. {@code .aclass}) and
     * {@code .as<type>} (e.g. {@code .asclass}) -- different SAP ADT
     * versions emit different forms (observed: {@code .asprog} vs
     * {@code .aclass} on the same workbench). Empty string return =
     * unknown extension; caller falls back to keyword scan of the source.
     */
    static String adtExtensionToType(String ext) {
        if (ext == null) return "";
        return switch (ext) {
            // Programs / reports / includes
            case "aprog",       "asprog"       -> "PROG/P";
            case "ainclude",    "asinclude",
                 "aincl",       "asincl"       -> "PROG/I";
            // Classes / interfaces
            case "aclass",      "asclass",
                 "aabaptest",   "asabaptest"   -> "CLAS/OC";
            case "aintf",       "asintf"       -> "INTF/OI";
            // RAP behavior definitions
            case "abdef",       "asbdef"       -> "BDEF/BDO";
            // CDS family
            case "addls",       "asddls"       -> "DDLS/DF";
            case "adcls",       "asdcls"       -> "DCLS/DL";
            case "addlx",       "asddlx"       -> "DDLX/EX";
            case "asrvd",       "assrvd"       -> "SRVD/SRV";
            case "asrvb",       "assrvb"       -> "SRVB/SVB";
            // DDIC structures / tables / data elements / domains
            case "astruc",      "asstruc",
                 "astruct",     "asstruct"     -> "TABL/DS";
            case "atabl",       "astabl"       -> "TABL/DT";
            case "adtel",       "asdtel"       -> "DTEL/DE";
            case "adoma",       "asdoma"       -> "DOMA/DD";
            // Function groups / function modules
            case "afugr",       "asfugr"       -> "FUGR/F";
            case "afunc",       "asfunc"       -> "FUGR/FF";
            // Message classes
            case "amsag",       "asmsag"       -> "MSAG/N";
            // Type groups
            case "atypegroup",  "astypegroup",
                 "atgrp",       "astgrp"       -> "TYPE/TG";
            // Search helps / lock objects
            case "ashlp",       "asshlp"       -> "SHLP/HS";
            case "aenqu",       "asenqu"       -> "ENQU/SL";
            default -> "";
        };
    }

    // ─── Reflection helpers ─────────────────────────────────────────────

    private static IEditorPart activeEditor() {
        try {
            IWorkbenchWindow win = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (win == null || win.getActivePage() == null) return null;
            return win.getActivePage().getActiveEditor();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String invokeStringGetter(Class<?> ifc, Object ref, String methodName) {
        try {
            Object out = ifc.getMethod(methodName).invoke(ref);
            return out instanceof String s ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
