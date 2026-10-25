package io.tieringkv;

/**
 * 服务版本（ADR-0355）：优先取 jar MANIFEST 的 Implementation-Version，
 * 本地开发（未打包）回退到源码版本常量。
 */
public final class ServerVersion {

    /** 与 pom revision 同步的源码版本。 */
    public static final String SOURCE_VERSION = "5.0.3";

    private ServerVersion() {
    }

    public static String current() {
        Package pkg = ServerVersion.class.getPackage();
        String impl = pkg == null ? null : pkg.getImplementationVersion();
        return impl == null || impl.isBlank() ? SOURCE_VERSION : impl;
    }
}
