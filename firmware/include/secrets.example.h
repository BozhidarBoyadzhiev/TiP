// Copy this file to secrets.h in the same folder and fill in your values.
// secrets.h is git ignored. Never commit it and never paste it anywhere.
#pragma once

// The WiFi networks the panda may join, 1 to 4, each {"ssid", "password"} and separated by commas.
// 2.4 GHz only. An open network has the password "". The panda joins the strongest one it can see
// and moves to the next when a join fails. For two networks:
//   #define TIP_WIFI_NETWORKS {"home-wifi", "home-password"}, {"phone-hotspot", "hotspot-password"}
#define TIP_WIFI_NETWORKS {"your-wifi-name", "your-wifi-password"}

// The device the panda connects as. The server logs the demo device's id on startup
// ("Demo device demo-panda has id ..."); the secret is TIP_DEMO_DEVICE_SECRET of that server.
#define TIP_DEVICE_ID "00000000-0000-0000-0000-000000000000"
#define TIP_DEVICE_SECRET "your-device-secret"
