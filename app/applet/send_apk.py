import os
import requests

bot_token = "8574074815:AAGOwf_yr4dxXpPVDyffn8cJh18AOtOP_Hs"
chat_id = "8157285805"
apk_path = "app/build/outputs/apk/debug/app-debug.apk"

url = f"https://api.telegram.org/bot{bot_token}/sendDocument"

if not os.path.exists(apk_path):
    print(f"Error: APK not found at {apk_path}")
    exit(1)

with open(apk_path, "rb") as f:
    files = {"document": ("app.apk", f, "application/vnd.android.package-archive")}
    data = {"chat_id": chat_id, "caption": "Here is your APK"}
    response = requests.post(url, data=data, files=files)
    print(response.status_code)
    print(response.text)
