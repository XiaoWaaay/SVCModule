package com.svcmonitor.app;

/**
 * MainActivity — 4-tab UI built programmatically (no XML layouts).
 *
 * Tabs: Monitor | Filter | Events | Settings
 *
 * CRITICAL: All tab views are pre-built in onCreate() BEFORE observeViewModel()
 *          to avoid UninitializedPropertyAccessException on lateinit properties.
 */
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u0092\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\b\n\u0002\b\t\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\u0010\t\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000b\n\u0002\b\u0002\n\u0002\u0010\"\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\f\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0010\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0002\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0002\b\u000f\n\u0002\u0018\u0002\n\u0002\b\u0006\n\u0002\u0018\u0002\n\u0002\b\n\n\u0002\u0010\r\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0006\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\b\n\u0002\u0018\u0002\n\u0002\b\r\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010$\n\u0002\b\u0014\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0003\u0018\u00002\u00020\u0001:\u0002\u00c9\u0001B\u0005\u00a2\u0006\u0002\u0010\u0002J\u0010\u0010e\u001a\u00020f2\u0006\u0010g\u001a\u00020\u0007H\u0002J\u0016\u0010h\u001a\u00020f2\f\u0010i\u001a\b\u0012\u0004\u0012\u00020%0\u0004H\u0002J\b\u0010j\u001a\u00020kH\u0002J\b\u0010l\u001a\u00020kH\u0002J\b\u0010m\u001a\u00020kH\u0002J!\u0010n\u001a\u00020\u00072\u0006\u0010o\u001a\u00020%2\u0006\u0010p\u001a\u00020\u0007H\u0082@\u00f8\u0001\u0000\u00a2\u0006\u0002\u0010qJ0\u0010r\u001a\u00020k2\u0006\u0010s\u001a\u00020k2\u0006\u0010t\u001a\u00020k2\u0006\u0010i\u001a\u00020k2\u0006\u0010u\u001a\u00020k2\u0006\u0010v\u001a\u00020kH\u0002J\b\u0010w\u001a\u00020kH\u0002J\b\u0010x\u001a\u00020kH\u0002J\u0016\u0010y\u001a\u00020\u00072\f\u0010z\u001a\b\u0012\u0004\u0012\u00020{0\u0004H\u0002J\b\u0010|\u001a\u00020fH\u0002J\u0010\u0010}\u001a\u00020\u000b2\u0006\u0010~\u001a\u00020\u000bH\u0002J\u0016\u0010\u007f\u001a\u00020f2\f\u0010i\u001a\b\u0012\u0004\u0012\u00020%0\u0004H\u0002J\u001d\u0010\u0080\u0001\u001a\u00020\u00072\b\u0010\u0081\u0001\u001a\u00030\u0082\u0001H\u0082@\u00f8\u0001\u0000\u00a2\u0006\u0003\u0010\u0083\u0001J\u001c\u0010\u0084\u0001\u001a\u00020\u00072\u0007\u0010\u0081\u0001\u001a\u00020%H\u0082@\u00f8\u0001\u0000\u00a2\u0006\u0003\u0010\u0085\u0001J\t\u0010\u0086\u0001\u001a\u00020fH\u0002J\t\u0010\u0087\u0001\u001a\u00020fH\u0002J\u0018\u0010\u0088\u0001\u001a\b\u0012\u0004\u0012\u00020\u001a0\u00042\u0007\u0010\u0089\u0001\u001a\u00020\u0007H\u0002J\u001d\u0010\u008a\u0001\u001a\b\u0012\u0004\u0012\u00020%0\u00042\f\u0010i\u001a\b\u0012\u0004\u0012\u00020%0\u0004H\u0002J\t\u0010\u008b\u0001\u001a\u00020 H\u0002J\u0013\u0010\u008c\u0001\u001a\u00030\u008d\u00012\u0007\u0010\u0089\u0001\u001a\u00020\u0007H\u0002J\n\u0010\u008e\u0001\u001a\u00030\u008f\u0001H\u0002J\u0017\u0010\u0090\u0001\u001a\u00020f2\f\u0010i\u001a\b\u0012\u0004\u0012\u00020%0\u0004H\u0002J\t\u0010\u0091\u0001\u001a\u00020fH\u0002J\u0018\u0010\u0092\u0001\u001a\b\u0012\u0004\u0012\u00020\u00050\u00042\u0007\u0010\u0093\u0001\u001a\u00020\u0007H\u0002J%\u0010\u0094\u0001\u001a\u00020\u001e2\u001a\u0010\u0095\u0001\u001a\u0015\u0012\u0004\u0012\u00020\u001e\u0012\u0004\u0012\u00020f0\u0096\u0001\u00a2\u0006\u0003\b\u0097\u0001H\u0002J\u0012\u0010\u0098\u0001\u001a\u00020,2\u0007\u0010\u0089\u0001\u001a\u00020\u0007H\u0002J\u0012\u0010\u0099\u0001\u001a\u00020,2\u0007\u0010\u0089\u0001\u001a\u00020\u0007H\u0002J\u0012\u0010\u009a\u0001\u001a\u00020 2\u0007\u0010\u0089\u0001\u001a\u00020\u0007H\u0002J\u0012\u0010\u009b\u0001\u001a\u00020 2\u0007\u0010\u009c\u0001\u001a\u00020\u000bH\u0002J\t\u0010\u009d\u0001\u001a\u00020fH\u0002J\u0015\u0010\u009e\u0001\u001a\u00020f2\n\u0010\u009f\u0001\u001a\u0005\u0018\u00010\u00a0\u0001H\u0014J\t\u0010\u00a1\u0001\u001a\u00020fH\u0014J\t\u0010\u00a2\u0001\u001a\u00020fH\u0002J\u0017\u0010\u00a3\u0001\u001a\u00020f2\f\u0010i\u001a\b\u0012\u0004\u0012\u00020%0\u0004H\u0002J!\u0010\u00a4\u0001\u001a\u00020 2\f\u0010i\u001a\b\u0012\u0004\u0012\u00020%0\u0004H\u0082@\u00f8\u0001\u0000\u00a2\u0006\u0003\u0010\u00a5\u0001J\u0012\u0010\u00a6\u0001\u001a\u00020 2\u0007\u0010\u00a7\u0001\u001a\u00020\u0007H\u0002J\t\u0010\u00a8\u0001\u001a\u00020fH\u0002J\u0018\u0010\u00a9\u0001\u001a\u00020f2\r\u0010\u00aa\u0001\u001a\b\u0012\u0004\u0012\u00020\u000b0\u0004H\u0002J\u0012\u0010\u00ab\u0001\u001a\u00020f2\u0007\u0010\u0093\u0001\u001a\u00020\u0007H\u0002J\u0019\u0010\u00ac\u0001\u001a\u00020f2\u000e\u0010\u00ad\u0001\u001a\t\u0012\u0005\u0012\u00030\u00ae\u00010\u0004H\u0002J\u0018\u0010\u00af\u0001\u001a\u00020f2\r\u0010\u00aa\u0001\u001a\b\u0012\u0004\u0012\u00020\u000b0\u0004H\u0002J)\u0010\u00b0\u0001\u001a\u000f\u0012\u0004\u0012\u00020\u0007\u0012\u0004\u0012\u00020\u00070\u00b1\u00012\u0007\u0010\u0081\u0001\u001a\u00020%H\u0082@\u00f8\u0001\u0000\u00a2\u0006\u0003\u0010\u0085\u0001J%\u0010\u00b2\u0001\u001a\u00020f2\u0007\u0010\u00b3\u0001\u001a\u0002012\u0007\u0010\u00b4\u0001\u001a\u00020\u001aH\u0082@\u00f8\u0001\u0000\u00a2\u0006\u0003\u0010\u00b5\u0001J\u001c\u0010\u00b6\u0001\u001a\u00020f2\b\u0010\u00b7\u0001\u001a\u00030\u008f\u00012\u0007\u0010\u00b8\u0001\u001a\u00020\u0007H\u0002J\t\u0010\u00b9\u0001\u001a\u00020fH\u0002J\u0011\u0010\u00ba\u0001\u001a\u00020f2\u0006\u0010o\u001a\u00020%H\u0002J\u0012\u0010\u00bb\u0001\u001a\u00020f2\u0007\u0010\u00bc\u0001\u001a\u00020\u000bH\u0002J\t\u0010\u00bd\u0001\u001a\u00020fH\u0002J\t\u0010\u00be\u0001\u001a\u00020fH\u0002J\t\u0010\u00bf\u0001\u001a\u00020fH\u0002J\t\u0010\u00c0\u0001\u001a\u00020fH\u0002J\t\u0010\u00c1\u0001\u001a\u00020fH\u0002J\t\u0010\u00c2\u0001\u001a\u00020 H\u0002J\t\u0010\u00c3\u0001\u001a\u00020fH\u0002J\u0017\u0010\u00c4\u0001\u001a\u00020f2\f\u0010i\u001a\b\u0012\u0004\u0012\u00020%0\u0004H\u0002J8\u0010\u00c5\u0001\u001a\u00020f*\u00020d2 \u0010\u0095\u0001\u001a\u001b\b\u0001\u0012\u000b\u0012\t\u0012\u0004\u0012\u00020f0\u00c6\u0001\u0012\u0007\u0012\u0005\u0018\u00010\u00c7\u00010\u0096\u0001H\u0002\u00f8\u0001\u0000\u00a2\u0006\u0003\u0010\u00c8\u0001R\u0014\u0010\u0003\u001a\b\u0012\u0004\u0012\u00020\u00050\u0004X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0006\u001a\u00020\u0007X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\b\u001a\u00020\tX\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010\n\u001a\u00020\u000bX\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\f\u001a\u00020\u000bX\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\r\u001a\u00020\u000bX\u0082D\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u000e\u001a\u00020\u000bX\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u000f\u001a\u00020\u000bX\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0010\u001a\u00020\u000bX\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0011\u001a\u00020\u000bX\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0012\u001a\u00020\u000bX\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0014\u0010\u0013\u001a\b\u0012\u0004\u0012\u00020\u000b0\u0004X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0014\u001a\u00020\u0015X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0016\u001a\u00020\u0015X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0017\u001a\u00020\u0015X\u0082.\u00a2\u0006\u0002\n\u0000R\u001a\u0010\u0018\u001a\u000e\u0012\u0004\u0012\u00020\u001a\u0012\u0004\u0012\u00020\u00070\u0019X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u001a\u0010\u001b\u001a\u000e\u0012\u0004\u0012\u00020\u001a\u0012\u0004\u0012\u00020\u00070\u0019X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u001c\u001a\u00020\u0007X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u001d\u001a\u00020\u001eX\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u001f\u001a\u00020 X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010!\u001a\u00020\u001aX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0014\u0010\"\u001a\b\u0012\u0004\u0012\u00020\u000b0#X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0014\u0010$\u001a\b\u0012\u0004\u0012\u00020%0\u0004X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010&\u001a\u00020\u001eX\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010\'\u001a\u00020\u001eX\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010(\u001a\u00020\u001eX\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010)\u001a\u00020*X\u0082.\u00a2\u0006\u0002\n\u0000R\u001a\u0010+\u001a\u000e\u0012\u0004\u0012\u00020\u000b\u0012\u0004\u0012\u00020,0\u0019X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010-\u001a\u00020 X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010.\u001a\u00020\u000bX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0014\u0010/\u001a\b\u0012\u0004\u0012\u00020100X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u00102\u001a\u00020 X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0010\u00103\u001a\u0004\u0018\u000104X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u00105\u001a\u00020\u000bX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0010\u00106\u001a\u0004\u0018\u000107X\u0082\u000e\u00a2\u0006\u0002\n\u0000R#\u00108\u001a\n :*\u0004\u0018\u000109098BX\u0082\u0084\u0002\u00a2\u0006\f\n\u0004\b=\u0010>\u001a\u0004\b;\u0010<R\u000e\u0010?\u001a\u00020 X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010@\u001a\u00020\u0007X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0010\u0010A\u001a\u0004\u0018\u000104X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010B\u001a\u00020\u001aX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010C\u001a\u00020\u0007X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010D\u001a\u00020\u000bX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0014\u0010E\u001a\b\u0012\u0004\u0012\u00020%0FX\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010G\u001a\u00020 X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010H\u001a\u00020 X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010I\u001a\u00020JX\u0082.\u00a2\u0006\u0002\n\u0000R!\u0010K\u001a\b\u0012\u0004\u0012\u00020L0\u00048BX\u0082\u0084\u0002\u00a2\u0006\f\n\u0004\bO\u0010>\u001a\u0004\bM\u0010NR\u000e\u0010P\u001a\u00020QX\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010R\u001a\u00020SX\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010T\u001a\u00020SX\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010U\u001a\u00020SX\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010V\u001a\u00020SX\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010W\u001a\u00020,X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010X\u001a\u00020,X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010Y\u001a\u00020,X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010Z\u001a\u00020,X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010[\u001a\u00020,X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010\\\u001a\u00020,X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010]\u001a\u00020,X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010^\u001a\u00020,X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010_\u001a\u00020,X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010`\u001a\u00020\u0015X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010a\u001a\u00020,X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010b\u001a\u00020,X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010c\u001a\u00020dX\u0082.\u00a2\u0006\u0002\n\u0000\u0082\u0002\u0004\n\u0002\b\u0019\u00a8\u0006\u00ca\u0001"}, d2 = {"Lcom/svcmonitor/app/MainActivity;", "Landroidx/appcompat/app/AppCompatActivity;", "()V", "appList", "", "Lcom/svcmonitor/app/AppInfo;", "appSearchQuery", "", "btnStartStop", "Landroid/widget/Button;", "cAccent", "", "cBg", "cCard", "cGreen", "cPrimary", "cRed", "cSecondary", "cText", "currentNrList", "etAllNrFilter", "Landroid/widget/EditText;", "etAppSearch", "etEventSearch", "eventCallChain", "Ljava/util/HashMap;", "", "eventSearchExtra", "eventSearchQuery", "filterListContainer", "Landroid/widget/LinearLayout;", "hideSystemApps", "", "historyLastSeq", "hookedNrSet", "", "lastEventsAll", "Lcom/svcmonitor/app/StatusParser$SvcEvent;", "llAllNrList", "llEventList", "llSelectedNrs", "logExporter", "Lcom/svcmonitor/app/LogExporter;", "nrNameViews", "Landroid/widget/TextView;", "onlyLaunchableApps", "pcServerBacklogLimit", "pcServerClients", "Ljava/util/ArrayList;", "Ljava/io/BufferedWriter;", "pcServerEnabled", "pcServerJob", "Lkotlinx/coroutines/Job;", "pcServerPort", "pcServerSocket", "Ljava/net/ServerSocket;", "prefs", "Landroid/content/SharedPreferences;", "kotlin.jvm.PlatformType", "getPrefs", "()Landroid/content/SharedPreferences;", "prefs$delegate", "Lkotlin/Lazy;", "relayEnabled", "relayHost", "relayJob", "relayLastEnqueuedSeq", "relayLastError", "relayPort", "relayQueue", "Ljava/util/ArrayDeque;", "resolvingChain", "resolvingSearch", "scrollEvents", "Landroid/widget/ScrollView;", "sensitiveRules", "Lcom/svcmonitor/app/MainActivity$SensitiveRule;", "getSensitiveRules", "()Ljava/util/List;", "sensitiveRules$delegate", "spinnerApp", "Landroid/widget/Spinner;", "switchDoFilpOpen", "Landroid/widget/Switch;", "switchHideSystemApps", "switchOnlyLaunchableApps", "switchTier2", "tvDashNrCount", "tvDashNrList", "tvEventCount", "tvEvtCount", "tvMonState", "tvMsg", "tvNrCount", "tvNrList", "tvStatus", "tvSuperKey", "tvUid", "tvVersion", "vm", "Lcom/svcmonitor/app/MainViewModel;", "applyPresetUi", "", "presetId", "broadcastPcServerEvents", "events", "buildDashboardTab", "Landroid/view/View;", "buildEventsTab", "buildFilterTab", "buildFpCallChain", "evt", "callerResolved", "(Lcom/svcmonitor/app/StatusParser$SvcEvent;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "buildMainLayout", "dashboard", "filter", "thread", "settings", "buildSettingsTab", "buildThreadTab", "buildThreadTree", "edges", "Lcom/svcmonitor/app/db/ThreadEdge;", "clearHistory", "dp", "v", "enqueueRelayEvents", "entityToJsonLineWithResolved", "e", "Lcom/svcmonitor/app/db/SvcEventEntity;", "(Lcom/svcmonitor/app/db/SvcEventEntity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "eventToJsonLineWithResolved", "(Lcom/svcmonitor/app/StatusParser$SvcEvent;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "exportCsv", "exportJson", "extractHexAddrs", "text", "filterEvents", "getRelayStats", "highlightSensitive", "", "historyFile", "Ljava/io/File;", "kickResolveCallChain", "kickResolveForSearch", "loadVisibleApps", "query", "makeCard", "block", "Lkotlin/Function1;", "Lkotlin/ExtensionFunctionType;", "makeLabel", "makeValue", "matchesSensitive", "nrUsesFd", "nr", "observeViewModel", "onCreate", "savedInstanceState", "Landroid/os/Bundle;", "onDestroy", "onStartStopClick", "persistNewEvents", "postRelayBatch", "(Ljava/util/List;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "postRelayRaw", "body", "refreshAppSpinner", "refreshNrHighlights", "nrs", "renderAllNrList", "renderFilterList", "hooks", "Lcom/svcmonitor/app/StatusParser$HookInfo;", "renderSelectedNrs", "resolveEventFields", "", "sendPcServerBacklog", "w", "afterSeq", "(Ljava/io/BufferedWriter;JLkotlin/coroutines/Continuation;)Ljava/lang/Object;", "shareFile", "file", "mimeType", "shareHistory", "showEventDetail", "showPidSidebar", "pid", "startFloatingMonitorOverlay", "startPcServer", "startRelay", "stopPcServer", "stopRelay", "testRelayOnce", "tryAutoGrantOverlayByRoot", "updateEventList", "viewModelScope_launch", "Lkotlin/coroutines/Continuation;", "", "(Lcom/svcmonitor/app/MainViewModel;Lkotlin/jvm/functions/Function1;)V", "SensitiveRule", "app_debug"})
public final class MainActivity extends androidx.appcompat.app.AppCompatActivity {
    private com.svcmonitor.app.MainViewModel vm;
    private com.svcmonitor.app.LogExporter logExporter;
    private android.widget.TextView tvStatus;
    private android.widget.TextView tvVersion;
    private android.widget.TextView tvUid;
    private android.widget.TextView tvEventCount;
    private android.widget.TextView tvMonState;
    private android.widget.TextView tvMsg;
    private android.widget.Button btnStartStop;
    private android.widget.EditText etAppSearch;
    private android.widget.Spinner spinnerApp;
    private android.widget.TextView tvDashNrCount;
    private android.widget.TextView tvDashNrList;
    private android.widget.TextView tvEvtCount;
    private android.widget.EditText etEventSearch;
    private android.widget.LinearLayout llEventList;
    private android.widget.ScrollView scrollEvents;
    private android.widget.Switch switchTier2;
    private android.widget.Switch switchHideSystemApps;
    private android.widget.Switch switchOnlyLaunchableApps;
    private android.widget.EditText tvSuperKey;
    private android.widget.TextView tvNrCount;
    private android.widget.TextView tvNrList;
    private android.widget.LinearLayout llSelectedNrs;
    private android.widget.Switch switchDoFilpOpen;
    @org.jetbrains.annotations.NotNull
    private final java.util.HashMap<java.lang.Integer, android.widget.TextView> nrNameViews = null;
    private android.widget.LinearLayout filterListContainer;
    private android.widget.EditText etAllNrFilter;
    private android.widget.LinearLayout llAllNrList;
    @org.jetbrains.annotations.NotNull
    private java.util.Set<java.lang.Integer> hookedNrSet;
    @org.jetbrains.annotations.NotNull
    private java.util.List<java.lang.Integer> currentNrList;
    @org.jetbrains.annotations.NotNull
    private java.util.List<com.svcmonitor.app.StatusParser.SvcEvent> lastEventsAll;
    @org.jetbrains.annotations.NotNull
    private java.lang.String eventSearchQuery = "";
    private long historyLastSeq = 0L;
    @org.jetbrains.annotations.NotNull
    private final java.util.HashMap<java.lang.Long, java.lang.String> eventSearchExtra = null;
    @org.jetbrains.annotations.NotNull
    private final java.util.HashMap<java.lang.Long, java.lang.String> eventCallChain = null;
    private boolean resolvingSearch = false;
    private boolean resolvingChain = false;
    private boolean relayEnabled = false;
    @org.jetbrains.annotations.NotNull
    private java.lang.String relayHost = "127.0.0.1";
    private int relayPort = 5001;
    private long relayLastEnqueuedSeq = 0L;
    @org.jetbrains.annotations.NotNull
    private final java.util.ArrayDeque<com.svcmonitor.app.StatusParser.SvcEvent> relayQueue = null;
    @org.jetbrains.annotations.Nullable
    private kotlinx.coroutines.Job relayJob;
    @org.jetbrains.annotations.NotNull
    private java.lang.String relayLastError = "";
    private boolean pcServerEnabled = false;
    private int pcServerPort = 8080;
    @org.jetbrains.annotations.Nullable
    private kotlinx.coroutines.Job pcServerJob;
    @org.jetbrains.annotations.Nullable
    private java.net.ServerSocket pcServerSocket;
    @org.jetbrains.annotations.NotNull
    private final java.util.ArrayList<java.io.BufferedWriter> pcServerClients = null;
    private int pcServerBacklogLimit = 20000;
    @org.jetbrains.annotations.NotNull
    private final kotlin.Lazy sensitiveRules$delegate = null;
    @org.jetbrains.annotations.NotNull
    private java.util.List<com.svcmonitor.app.AppInfo> appList;
    @org.jetbrains.annotations.NotNull
    private java.lang.String appSearchQuery = "";
    private boolean hideSystemApps = false;
    private boolean onlyLaunchableApps = false;
    @org.jetbrains.annotations.NotNull
    private final kotlin.Lazy prefs$delegate = null;
    private final int cPrimary = 0;
    private final int cBg = 0;
    private final int cCard = android.graphics.Color.WHITE;
    private final int cText = 0;
    private final int cSecondary = 0;
    private final int cGreen = 0;
    private final int cRed = 0;
    private final int cAccent = 0;
    
    public MainActivity() {
        super();
    }
    
    private final java.util.List<com.svcmonitor.app.MainActivity.SensitiveRule> getSensitiveRules() {
        return null;
    }
    
    private final android.content.SharedPreferences getPrefs() {
        return null;
    }
    
    @java.lang.Override
    protected void onCreate(@org.jetbrains.annotations.Nullable
    android.os.Bundle savedInstanceState) {
    }
    
    private final android.view.View buildMainLayout(android.view.View dashboard, android.view.View filter, android.view.View events, android.view.View thread, android.view.View settings) {
        return null;
    }
    
    private final android.view.View buildDashboardTab() {
        return null;
    }
    
    private final void startFloatingMonitorOverlay() {
    }
    
    private final void tryAutoGrantOverlayByRoot() {
    }
    
    private final android.view.View buildFilterTab() {
        return null;
    }
    
    private final void renderAllNrList(java.lang.String query) {
    }
    
    private final android.view.View buildEventsTab() {
        return null;
    }
    
    private final android.view.View buildThreadTab() {
        return null;
    }
    
    private final java.lang.String buildThreadTree(java.util.List<com.svcmonitor.app.db.ThreadEdge> edges) {
        return null;
    }
    
    private final android.view.View buildSettingsTab() {
        return null;
    }
    
    private final java.util.List<com.svcmonitor.app.AppInfo> loadVisibleApps(java.lang.String query) {
        return null;
    }
    
    private final void refreshAppSpinner() {
    }
    
    private final void observeViewModel() {
    }
    
    private final void updateEventList(java.util.List<com.svcmonitor.app.StatusParser.SvcEvent> events) {
    }
    
    private final java.util.List<com.svcmonitor.app.StatusParser.SvcEvent> filterEvents(java.util.List<com.svcmonitor.app.StatusParser.SvcEvent> events) {
        return null;
    }
    
    private final java.lang.CharSequence highlightSensitive(java.lang.String text) {
        return null;
    }
    
    private final void showPidSidebar(int pid) {
    }
    
    private final boolean matchesSensitive(java.lang.String text) {
        return false;
    }
    
    private final void startRelay() {
    }
    
    private final void stopRelay() {
    }
    
    private final void startPcServer() {
    }
    
    private final void stopPcServer() {
    }
    
    private final java.lang.Object sendPcServerBacklog(java.io.BufferedWriter w, long afterSeq, kotlin.coroutines.Continuation<? super kotlin.Unit> $completion) {
        return null;
    }
    
    private final java.lang.Object entityToJsonLineWithResolved(com.svcmonitor.app.db.SvcEventEntity e, kotlin.coroutines.Continuation<? super java.lang.String> $completion) {
        return null;
    }
    
    private final java.lang.Object eventToJsonLineWithResolved(com.svcmonitor.app.StatusParser.SvcEvent e, kotlin.coroutines.Continuation<? super java.lang.String> $completion) {
        return null;
    }
    
    private final java.lang.Object resolveEventFields(com.svcmonitor.app.StatusParser.SvcEvent e, kotlin.coroutines.Continuation<? super java.util.Map<java.lang.String, java.lang.String>> $completion) {
        return null;
    }
    
    private final void broadcastPcServerEvents(java.util.List<com.svcmonitor.app.StatusParser.SvcEvent> events) {
    }
    
    private final void enqueueRelayEvents(java.util.List<com.svcmonitor.app.StatusParser.SvcEvent> events) {
    }
    
    private final java.lang.Object postRelayBatch(java.util.List<com.svcmonitor.app.StatusParser.SvcEvent> events, kotlin.coroutines.Continuation<? super java.lang.Boolean> $completion) {
        return null;
    }
    
    private final boolean postRelayRaw(java.lang.String body) {
        return false;
    }
    
    private final boolean testRelayOnce() {
        return false;
    }
    
    private final boolean getRelayStats() {
        return false;
    }
    
    private final void kickResolveCallChain(java.util.List<com.svcmonitor.app.StatusParser.SvcEvent> events) {
    }
    
    private final java.lang.Object buildFpCallChain(com.svcmonitor.app.StatusParser.SvcEvent evt, java.lang.String callerResolved, kotlin.coroutines.Continuation<? super java.lang.String> $completion) {
        return null;
    }
    
    private final void kickResolveForSearch() {
    }
    
    private final java.io.File historyFile() {
        return null;
    }
    
    private final void persistNewEvents(java.util.List<com.svcmonitor.app.StatusParser.SvcEvent> events) {
    }
    
    private final void shareHistory() {
    }
    
    private final void clearHistory() {
    }
    
    private final void showEventDetail(com.svcmonitor.app.StatusParser.SvcEvent evt) {
    }
    
    private final void exportCsv() {
    }
    
    private final void exportJson() {
    }
    
    private final void shareFile(java.io.File file, java.lang.String mimeType) {
    }
    
    private final void onStartStopClick() {
    }
    
    private final int dp(int v) {
        return 0;
    }
    
    private final android.widget.LinearLayout makeCard(kotlin.jvm.functions.Function1<? super android.widget.LinearLayout, kotlin.Unit> block) {
        return null;
    }
    
    private final android.widget.TextView makeLabel(java.lang.String text) {
        return null;
    }
    
    private final android.widget.TextView makeValue(java.lang.String text) {
        return null;
    }
    
    private final void refreshNrHighlights(java.util.List<java.lang.Integer> nrs) {
    }
    
    private final void renderFilterList(java.util.List<com.svcmonitor.app.StatusParser.HookInfo> hooks) {
    }
    
    private final void renderSelectedNrs(java.util.List<java.lang.Integer> nrs) {
    }
    
    private final void applyPresetUi(java.lang.String presetId) {
    }
    
    private final boolean nrUsesFd(int nr) {
        return false;
    }
    
    private final java.util.List<java.lang.Long> extractHexAddrs(java.lang.String text) {
        return null;
    }
    
    private final void viewModelScope_launch(com.svcmonitor.app.MainViewModel $this$viewModelScope_launch, kotlin.jvm.functions.Function1<? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends java.lang.Object> block) {
    }
    
    @java.lang.Override
    protected void onDestroy() {
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000 \n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\b\n\u0002\b\t\n\u0002\u0010\u000b\n\u0002\b\u0004\b\u0082\b\u0018\u00002\u00020\u0001B\u0015\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0005\u00a2\u0006\u0002\u0010\u0006J\t\u0010\u000b\u001a\u00020\u0003H\u00c6\u0003J\t\u0010\f\u001a\u00020\u0005H\u00c6\u0003J\u001d\u0010\r\u001a\u00020\u00002\b\b\u0002\u0010\u0002\u001a\u00020\u00032\b\b\u0002\u0010\u0004\u001a\u00020\u0005H\u00c6\u0001J\u0013\u0010\u000e\u001a\u00020\u000f2\b\u0010\u0010\u001a\u0004\u0018\u00010\u0001H\u00d6\u0003J\t\u0010\u0011\u001a\u00020\u0005H\u00d6\u0001J\t\u0010\u0012\u001a\u00020\u0003H\u00d6\u0001R\u0011\u0010\u0004\u001a\u00020\u0005\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0007\u0010\bR\u0011\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\t\u0010\n\u00a8\u0006\u0013"}, d2 = {"Lcom/svcmonitor/app/MainActivity$SensitiveRule;", "", "needle", "", "color", "", "(Ljava/lang/String;I)V", "getColor", "()I", "getNeedle", "()Ljava/lang/String;", "component1", "component2", "copy", "equals", "", "other", "hashCode", "toString", "app_debug"})
    static final class SensitiveRule {
        @org.jetbrains.annotations.NotNull
        private final java.lang.String needle = null;
        private final int color = 0;
        
        public SensitiveRule(@org.jetbrains.annotations.NotNull
        java.lang.String needle, int color) {
            super();
        }
        
        @org.jetbrains.annotations.NotNull
        public final java.lang.String getNeedle() {
            return null;
        }
        
        public final int getColor() {
            return 0;
        }
        
        @org.jetbrains.annotations.NotNull
        public final java.lang.String component1() {
            return null;
        }
        
        public final int component2() {
            return 0;
        }
        
        @org.jetbrains.annotations.NotNull
        public final com.svcmonitor.app.MainActivity.SensitiveRule copy(@org.jetbrains.annotations.NotNull
        java.lang.String needle, int color) {
            return null;
        }
        
        @java.lang.Override
        public boolean equals(@org.jetbrains.annotations.Nullable
        java.lang.Object other) {
            return false;
        }
        
        @java.lang.Override
        public int hashCode() {
            return 0;
        }
        
        @java.lang.Override
        @org.jetbrains.annotations.NotNull
        public java.lang.String toString() {
            return null;
        }
    }
}