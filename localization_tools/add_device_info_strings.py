#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""向全部语言目录添加设备信息弹窗字符串（DeviceInfoDialog），并更新 chart_guide_connect。

用法：
    py add_device_info_strings.py
幂等：已包含 label_device_id 的文件自动跳过。添加后请运行 check_keys.py 校验。
"""
import io
import os
import re

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")

# (label_device_id, label_manufacturer, label_model, label_serial_number,
#  label_firmware_version, label_software_version, btn_close, chart_guide_connect)
DATA = {
    "values":        ("设备ID", "厂商", "型号", "序列号", "固件版本", "软件版本", "关闭",
                      "点击蓝牙图标连接/查看设备信息，长按断开"),
    "values-en":     ("Device ID", "Manufacturer", "Model", "Serial No.", "Firmware Version", "Software Version", "Close",
                      "Tap bluetooth icon to connect or view device info, long press to disconnect"),
    "values-zh-rTW": ("裝置ID", "製造商", "型號", "序號", "韌體版本", "軟體版本", "關閉",
                      "點擊藍牙圖示連線/查看裝置資訊，長按斷開"),
    "values-de":     ("Geräte-ID", "Hersteller", "Modell", "Seriennummer", "Firmware-Version", "Software-Version", "Schließen",
                      "Tippen Sie auf das Bluetooth-Symbol zum Verbinden oder Anzeigen der Geräteinfo, langes Drücken zum Trennen"),
    "values-es":     ("ID del dispositivo", "Fabricante", "Modelo", "N.º de serie", "Versión de firmware", "Versión de software", "Cerrar",
                      "Toca el icono de Bluetooth para conectar o ver la información del dispositivo, mantén pulsado para desconectar"),
    "values-fr":     ("ID de l\\'appareil", "Fabricant", "Modèle", "N° de série", "Version du firmware", "Version du logiciel", "Fermer",
                      "Appuyez sur l\\'icône Bluetooth pour connecter ou afficher les infos de l\\'appareil, maintenez pour déconnecter"),
    "values-it":     ("ID del dispositivo", "Produttore", "Modello", "Numero di serie", "Versione firmware", "Versione software", "Chiudi",
                      "Tocca l\\'icona Bluetooth per connettere o visualizzare le informazioni del dispositivo, premi a lungo per disconnettere"),
    "values-ja":     ("デバイスID", "製造元", "型番", "シリアル番号", "ファームウェアバージョン", "ソフトウェアバージョン", "閉じる",
                      "Bluetoothアイコンをタップして接続・デバイス情報を表示、長押しで切断"),
    "values-ko":     ("기기 ID", "제조사", "모델", "일련번호", "펌웨어 버전", "소프트웨어 버전", "닫기",
                      "블루투스 아이콘을 탭하여 연결 또는 기기 정보 확인, 길게 눌러 연결 해제"),
    "values-nl":     ("Apparaat-ID", "Fabrikant", "Model", "Serienummer", "Firmwareversie", "Softwareversie", "Sluiten",
                      "Tik op het bluetoothpictogram om te verbinden of apparaatinfo te bekijken, houd ingedrukt om te verbreken"),
    "values-pl":     ("ID urządzenia", "Producent", "Model", "Numer seryjny", "Wersja firmware", "Wersja oprogramowania", "Zamknij",
                      "Dotknij ikony Bluetooth, aby połączyć lub wyświetlić informacje o urządzeniu, przytrzymaj, aby rozłączyć"),
    "values-pt":     ("ID do dispositivo", "Fabricante", "Modelo", "N.º de série", "Versão do firmware", "Versão do software", "Fechar",
                      "Toque no ícone de Bluetooth para conectar ou ver as informações do dispositivo, pressione e segure para desconectar"),
    "values-ru":     ("ID устройства", "Производитель", "Модель", "Серийный номер", "Версия прошивки", "Версия ПО", "Закрыть",
                      "Нажмите значок Bluetooth для подключения или просмотра информации об устройстве, долгое нажатие — для отключения"),
}

KEYS = ["label_device_id", "label_manufacturer", "label_model", "label_serial_number",
        "label_firmware_version", "label_software_version", "btn_close"]


def main():
    for dirname, vals in DATA.items():
        path = os.path.join(RES, dirname, "strings.xml")
        with io.open(path, encoding="utf-8") as f:
            content = f.read()
        if "label_device_id" in content:
            print("SKIP (already added):", dirname)
            continue

        # 1) 更新 chart_guide_connect（repl 中反斜杠需转义）
        new_connect = '    <string name="chart_guide_connect">%s</string>' % vals[7]
        content, n = re.subn(
            r'[ \t]*<string name="chart_guide_connect">.*?</string>',
            new_connect.replace("\\", "\\\\"),
            content, flags=re.S)
        if n != 1:
            raise SystemExit("chart_guide_connect 替换失败: " + dirname)

        # 2) 在 label_heart_rate 行后插入 DeviceInfoDialog 块
        block = "\n\n    <!-- DeviceInfoDialog -->\n" + "\n".join(
            '    <string name="%s">%s</string>' % (k, v)
            for k, v in zip(KEYS, vals[:7]))
        lines = content.split("\n")
        idx = next(i for i, l in enumerate(lines) if 'name="label_heart_rate"' in l)
        lines.insert(idx + 1, block)
        content = "\n".join(lines)

        with io.open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        print("OK:", dirname)


if __name__ == "__main__":
    main()
