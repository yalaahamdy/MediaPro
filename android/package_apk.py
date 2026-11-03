#!/usr/bin/env python3
"""
package_apk.py — Packages MediaPro Android APK with exact forward slashes
and uncompressed assets to ensure 100% compatibility across all phones and tablets.
"""
import sys
import os
import zipfile
import time

def package_apk(unaligned_apk, dex_file, html_file, output_apk):
    print(f"Packaging {output_apk}...")
    
    with open(dex_file, "rb") as f:
        dex_data = f.read()
        
    with open(html_file, "rb") as f:
        html_data = f.read()

    now = time.localtime(time.time())[:6]

    # Create new zip
    with zipfile.ZipFile(unaligned_apk, "r") as zin:
        with zipfile.ZipFile(output_apk, "w") as zout:
            # Copy all entries from unaligned apk except any accidental assets/ or classes.dex
            for item in zin.infolist():
                if item.filename.startswith("assets/") or item.filename == "classes.dex" or "\\" in item.filename:
                    continue
                data = zin.read(item.filename)
                zout.writestr(item, data)
                
            # Add classes.dex (compressed)
            dex_info = zipfile.ZipInfo("classes.dex", date_time=now)
            dex_info.compress_type = zipfile.ZIP_DEFLATED
            dex_info.external_attr = 0o644 << 16
            zout.writestr(dex_info, dex_data)
            
            # Add assets/www/MediaPro.html (ZIP_STORED for direct mmap zero-copy & no 1MB buffer limit)
            html_info = zipfile.ZipInfo("assets/www/MediaPro.html", date_time=now)
            html_info.compress_type = zipfile.ZIP_STORED
            html_info.external_attr = 0o644 << 16
            zout.writestr(html_info, html_data)
            
    print("Verification of entries in packaged APK:")
    with zipfile.ZipFile(output_apk, "r") as z:
        found = False
        for info in z.infolist():
            if "MediaPro.html" in info.filename:
                print(f"  -> Found asset: {info.filename!r} (compress_type={info.compress_type}, size={info.file_size})")
                assert info.filename == "assets/www/MediaPro.html", f"Invalid path separator in {info.filename}"
                assert info.compress_type == zipfile.ZIP_STORED, "Asset must be uncompressed (ZIP_STORED)"
                found = True
        assert found, "assets/www/MediaPro.html was not found in packaged APK!"
                
    print("Packaging completed successfully!")

if __name__ == "__main__":
    if len(sys.argv) < 5:
        print("Usage: package_apk.py <unaligned_apk> <dex_file> <html_file> <output_apk>")
        sys.exit(1)
    package_apk(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
