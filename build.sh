#!/bin/zsh
# 不依赖 Gradle：用 SDK 的 aapt2 / javac / d8 / apksigner 构建。输出 build/Mtz2ThemePark.apk
set -euo pipefail
cd "${0:A:h}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.1.0"
JAR="$SDK/platforms/android-36.1/android.jar"
B=build
rm -rf "$B"; mkdir -p "$B/classes" "$B/packclasses" "$B/gen" "$B/assets" "$B/dex" "$B/apksig"

# 生成的图标包内置的小 dex
javac -nowarn -source 8 -target 8 -Xlint:-options -bootclasspath "$JAR" -d "$B/packclasses" pack/com/chan/mtz2themepark/pack/PackActivity.java
"$BT/d8" --lib "$JAR" --min-api 26 --output "$B/dex" "$B/packclasses/com/chan/mtz2themepark/pack/PackActivity.class"
mv "$B/dex/classes.dex" "$B/assets/pack_classes.dex"

"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml -A "$B/assets" --java "$B/gen" "$B/res.zip"

# 只取签名库（Apache 2.0），不带 apksigner 命令行和 conscrypt
(cd "$B/apksig" && unzip -q "$BT/lib/apksigner.jar" 'com/android/apksig/*')
(cd "$B/apksig" && jar cf ../apksig.jar com)

# android.jar 没有 LambdaMetafactory，所以 java.* 用 JDK 的 Java 8 API，lambda 交给 d8 脱糖
javac -nowarn --release 8 -Xlint:-options -cp "$JAR:$B/apksig.jar" \
  -d "$B/classes" "$B"/gen/com/chan/mtz2themepark/R.java src/com/chan/mtz2themepark/*.java
(cd "$B/classes" && jar cf ../app.jar com)
"$BT/d8" --release --lib "$JAR" --min-api 33 --output "$B/dex" "$B/app.jar" "$B/apksig.jar" 2>&1 | grep -vE '^Warning|Type .* was not found|Missing class' || true
test -f "$B/dex/classes.dex"

cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -0 ../unsigned.apk classes*.dex)
"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

KS="${MTZ2APK_KEYSTORE:-signing/release.jks}"
PW="${KS:h}/password.txt"
if [[ ! -f "$KS" ]]; then
  mkdir -p "${KS:h}"
  openssl rand -hex 24 | tr -d '\n' > "$PW"; chmod 600 "$PW"
  keytool -genkeypair -keystore "$KS" -storepass "$(cat "$PW")" -keypass "$(cat "$PW")" -alias app -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=MTZ to Icon Pack" 2>/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "file:$PW" --out "$B/Mtz2ThemePark.apk" "$B/aligned.apk"
"$BT/apksigner" verify "$B/Mtz2ThemePark.apk"
ls -la "$B/Mtz2ThemePark.apk"
