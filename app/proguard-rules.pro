proguard placeholder

# adb/LibadbTls.kt clears libadb's cached TLS context by reflection, by name. Minify is off today;
# this keeps the name if it is ever turned on.
-keep class io.github.muntashirakon.adb.SslUtils {
    private static javax.net.ssl.SSLContext sslContext;
}
