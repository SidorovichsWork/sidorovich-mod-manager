# Sidorovich's Mod Manager

A free, independent Android mod installer for the Android port of S.T.A.L.K.E.R. Anomaly.

[Español](README.es.md) · [Русский](README.ru.md)

**Public beta · Android 8.0 or later · English, Español, Русский**

[Download the APK](https://github.com/SidorovichsWork/sidorovich-mod-manager/releases) · [Report a bug](https://github.com/SidorovichsWork/sidorovich-mod-manager/issues)

The interface starts in English. Use **Language** on the home screen to switch to Spanish or Russian. Mod authors' descriptions remain in their original language.

## Features

- Import ZIP, 7z and extracted folders using Android's folder picker.
- Configure supported FOMOD installers or choose and order components manually.
- Enable, disable and reorder mods; the last enabled mod takes priority.
- Review shared paths, back up replaced files, verify copies and recover interrupted operations.
- After testing a mod, choose **Keep installed and free space** to permanently integrate it and remove library and backup data that is no longer needed. Related mods may need to be integrated together. This action removes the ability to disable or uninstall those mods through the app.

## Installing mods

1. Install the APK and open the app. This public beta has a separate application ID from the earlier private test version.
2. Close Anomaly and select the port's `openxray` folder containing `fsgame.ltx`.
3. Import a mod, follow its author's instructions and select components.
4. Enable the mod, arrange its load order, review and apply changes.
5. Test in the game before permanently integrating anything.

To uninstall a managed mod, disable it and apply changes. Then remove it from the library if desired. If installation is interrupted, use **Recover installation** before proceeding.

Keep a separate copy of your saves. Do not clear the app's data or uninstall it while you need its backups. Use one manager installation for a game folder: the public beta does not import the private version's library or backups.

## Compatibility

The app installs `gamedata` resources. It does not install Windows executables, DLLs, engine replacements or arbitrary patching tools. RAR and split archives must be extracted first. Some FOMOD dependencies and installer features are unsupported; the app reports them instead of guessing. Copying files successfully does not guarantee that a mod works with the Android engine.

Existing manually installed mods are treated as the initial game state. Mods, game files and saves are not bundled. This is a community project and is not affiliated with GSC Game World, the Anomaly team or the Android port's developers.

## Building

Use JDK 17, Android SDK 35 and Gradle 8.9. Set `ANDROID_HOME`, or create a local `local.properties` containing `sdk.dir=...`. Run `gradle testDebugUnitTest lintDebug assembleDebug`. No signing secrets are needed for a development build.

Release signing reads `SMM_KEYSTORE`, `SMM_STORE_PASSWORD`, `SMM_KEY_ALIAS` and `SMM_KEY_PASSWORD` from the environment. Set these privately, then run `gradle testReleaseUnitTest lintRelease assembleRelease`. Keep your keystore and passwords outside this repository. The official signing key is not distributed.

Translations are Android string resources in `app/src/main/res/values`, `values-es` and `values-ru`. Keep the three catalogs in sync and retain leading/trailing spaces in message fragments. The localization tests check coverage and English fallback consistency.

## Support

Report bugs with the app version, Android version, port version, mod name and steps to reproduce. Remove personal information from logs before sharing them. Community support and maintenance are voluntary; donations do not purchase compatibility or priority support.

Optional support: [Sidorovich's Workshop on Patreon](https://www.patreon.com/cw/SidorovichsWorkshop).

Original app code is licensed under [MIT](LICENSE). Dependencies retain their own licenses.
