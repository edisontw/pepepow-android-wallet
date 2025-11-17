-keepattributes Exceptions, InnerClasses
-keep public class org.dash.wallet.common.** {
    public protected *;
}
-keep public interface org.dash.wallet.common.** {*;}
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn java.lang.ClassValue
