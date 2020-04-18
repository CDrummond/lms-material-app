# WORK IN PROGRESS

This is app is *very* much a work in progress, and has *many* rough edges. It is
not intended for daily use.

# Introduction
This application *requires* that you run LMS and have the Material-Skin plugin
installed. However, it is not required for Material to be set as the default
skin.

This app is based upon https://github.com/andreasbehnke/lms-material-app

## Building and signing the app

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
/lms-material-app/app/build/outputs/apk/release/app-release.apk 
```
