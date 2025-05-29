package main;

public class Util {
    public static boolean isLogin(String channelId, String userId) {
        if (Configuration.TEST_CHANNEL == null || Configuration.TEST_USER == null) {
            return false;
        }
        return channelId.equals(Configuration.TEST_CHANNEL) && userId.equals(Configuration.TEST_USER);
    }

    public static boolean isNotLogin(String channelId, String userId) {
        return !isLogin(channelId, userId);
    }
}
