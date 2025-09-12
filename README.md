# Introduction
This application *requires* that you run LMS and have the Material-Skin plugin
installed. However, it is not required for Material to be set as the default
skin.

This app is based upon https://github.com/andreasbehnke/lms-material-app
 
 [<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/com.craigd.lmsmaterial.app/) 
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" height="80" alt="Get it at IzzyOnDroid">](https://apt.izzysoft.de/packages/com.craigd.lmsmaterial.app)
 
# Building and signing the app

You can build the app using your own signing key. Only signed apk files can be
installed by downloading, so the signing process is required. 

Read this documentation for details: https://developer.android.com/studio/publish/app-signing

* create a jsk keystore and a key for signing app:
```
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-alias
```
* create file keystore.properties:
```
storePassword=myStorePassword
keyPassword=mykeyPassword
keyAlias=my-alias
storeFile=my-release-key.jks
```
* secure this file:
```
chmod 600 keystore.properties
```
* build release apk:
```
./gradlew assembleRelease
```
* move release artifact to your phone: 
```
/lms-material-app/lms-material/build/outputs/apk/release/lms-material-release.apk 
```

# Donations

I develop this skin purely for fun, so no donations are required. However, seeing as I have been asked about this a few times, here is a link...

[![paypal](https://www.paypalobjects.com/en_US/i/btn/btn_donateCC_LG.gif)](https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=2X2CTDUH27V9L&source=url)

# Licenses

This app is provided under the [MIT License](LICENSE).

The metadata in the `fastlane/metadata/android` directory is used by F-Droid but stored in this repository for convenience.  Much of it was taken from F-Droid, and it is hence provided under the [GNU Affero General Public License](fastlane/metadata/android/LICENSE).
