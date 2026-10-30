# Methods called from the reader's JavaScript.
-keepclassmembers class org.recto.reader.flow.FlowView$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Classes using newer Android APIs must stay separate (no inlining into callers), so older
# devices never have to resolve classes they do not have.
-keep,allowobfuscation class org.recto.reader.Compat$V30 { *; }
-keep,allowobfuscation class org.recto.reader.Compat$V33 { *; }
-keep,allowobfuscation class org.recto.reader.fixed.PdfSource$V35 { *; }
-keep,allowobfuscation class org.recto.reader.flow.FlowView$Client26 { *; }
