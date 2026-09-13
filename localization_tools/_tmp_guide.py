# -*- coding: utf-8 -*-
import io, os, re
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
NEW = {
    "values":        "点击蓝牙图标连接，点击设备查看信息，长按断开",
    "values-en":     "Tap bluetooth icon to connect, tap device for info, long press to disconnect",
    "values-zh-rTW": "點擊藍牙圖示連線，點擊裝置查看資訊，長按斷開",
    "values-de":     "Bluetooth-Symbol antippen zum Verbinden, Gerät antippen für Infos, langes Drücken zum Trennen",
    "values-es":     "Toca el icono de Bluetooth para conectar, toca el dispositivo para ver su información, mantén pulsado para desconectar",
    "values-fr":     "Appuyez sur l\'icône Bluetooth pour connecter, sur l\'appareil pour les infos, maintenez pour déconnecter",
    "values-it":     "Tocca l\'icona Bluetooth per connettere, il dispositivo per le informazioni, premi a lungo per disconnettere",
    "values-ja":     "Bluetoothアイコンをタップで接続、デバイスをタップで情報表示、長押しで切断",
    "values-ko":     "블루투스 아이콘을 탭하여 연결, 기기를 탭하여 정보 확인, 길게 눌러 연결 해제",
    "values-nl":     "Tik op het bluetoothpictogram om te verbinden, op het apparaat voor info, houd ingedrukt om te verbreken",
    "values-pl":     "Dotknij ikony Bluetooth, aby połączyć, urządzenia, aby zobaczyć informacje, przytrzymaj, aby rozłączyć",
    "values-pt":     "Toque no ícone de Bluetooth para conectar, no dispositivo para ver as informações, pressione e segure para desconectar",
    "values-ru":     "Нажмите значок Bluetooth для подключения, устройство — для информации, долгое нажатие — для отключения",
}
for d, v in NEW.items():
    p = os.path.join(RES, d, "strings.xml")
    with io.open(p, encoding="utf-8") as f:
        c = f.read()
    line = '    <string name="chart_guide_connect">%s</string>' % v
    c2, n = re.subn(r'[ \t]*<string name="chart_guide_connect">.*?</string>', line.replace("\\", "\\\\"), c, flags=re.S)
    assert n == 1, d
    with io.open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(c2)
    print("OK:", d)
