import requests

url = "https://catbox.moe/user/api.php"
print("Uploading to catbox.moe...")
with open("./.build-outputs/app-debug.apk", "rb") as f:
    files = {"fileToUpload": f}
    data = {"reqtype": "fileupload"}
    response = requests.post(url, data=data, files=files)
    print("Response status:", response.status_code)
    print("Response text:", response.text)
