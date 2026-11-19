package com.tom_roush.pdfbox.android

/** PdfBox-Android needs its resources unpacked first; Apache PDFBox on the desktop doesn't. */
object PDFBoxResourceLoader { fun init(ctx: android.content.Context) {} }
