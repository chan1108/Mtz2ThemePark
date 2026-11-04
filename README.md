# Mtz2ThemePark

专为三星 Good Lock 主题公园（Theme Park）打造：在手机上把小米主题（`.mtz`）转换成 Theme Park 可用的图标包 APK，不需要电脑。

## 功能

- 读取 mtz 里的静态图标，按包名和 Activity 匹配手机上已安装的应用
- 把三星系统应用映射到主题里的同类图标（相机、电话、相册等）
- 主题没有适配的应用，用主题自带的蒙版（`icon_mask`）、底板（`icon_pattern`）、描边（`icon_border`）生成同风格图标
- 在手机上直接生成并签名 APK，可以安装或另存

三星日历有意保留原生图标，这样才能显示实时日期。

## 使用

1. 安装转换应用，打开后选择 `.mtz` 文件，点“开始转换”。
2. 点“安装到手机”，在系统安装界面确认。第一次需要在系统设置里允许它安装应用；也可以点“另存 APK”后手动安装。
3. 打开 Good Lock → Theme Park → 图标，新建或编辑图标主题，选择生成的图标包，保存并应用。

在 Theme Park 里更换图标包后，“形状”可能被重置，需要重新选择。

## 限制

- 动态图标、锁屏、状态栏等是小米专用格式，不会转换。
- 只为转换时已安装的应用建立映射。新装应用后需要重新转换。
- 生成图标包的密钥保存在转换应用的 Android Keystore 里。卸载转换应用后，新生成的图标包无法覆盖安装旧版，要先卸载旧图标包。
- 目前只用一个主题在 Galaxy S25 Ultra（One UI 9）上测试过。

## 构建

需要 JDK 和 Android SDK（build-tools `36.1.0`、platform `android-36.1`），不需要 Gradle。

```bash
./build.sh
```

产物是 `build/Mtz2ThemePark.apk`。首次构建会在 `signing/` 生成签名密钥，这个目录不会提交到 Git，请自行备份。

## 工作原理

手机上没有 aapt2，所以 APK 由应用自己组装：

| 文件 | 作用 |
|---|---|
| `Axml.java` | 生成二进制清单和 `res/xml` |
| `Arsc.java` | 生成 `resources.arsc` |
| `ApkZip.java` | 写未压缩且 4 字节对齐的 ZIP |
| `PackSigner.java` | 用 apksig 和 Android Keystore 做 v2 签名 |
| `ThemeArchive.java` | 解析 mtz |
| `IconRenderer.java` | 套蒙版生成图标 |
| `Converter.java` | 串起整个转换流程 |

## 版权

本项目代码以 [MIT 许可证](LICENSE) 发布，第三方组件见 [NOTICE](NOTICE)。

本仓库不包含任何主题文件或图标。主题和图标的版权归各自作者所有，应用名称和图标归各自权利人所有。请只转换你有权使用的主题，生成的图标包请勿在未获授权时分发。本项目是非官方工具，与小米、三星没有任何关联，Theme Park 是三星的产品。
