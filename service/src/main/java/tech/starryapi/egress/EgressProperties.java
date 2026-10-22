package tech.starryapi.egress;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("starry.egress")
public class EgressProperties {
    private boolean mihomoEnabled;
    private String controllerUrl = "http://127.0.0.1:9090";
    private String controllerSecret = "";
    private String listenerHost = "127.0.0.1";
    private String listenerBind = "127.0.0.1";
    private String interfaceName = "";
    private boolean tlsEnabled;
    private String tlsUrl = "http://127.0.0.1:9091";
    private String tlsSecret = "";
    private Duration probeTimeout = Duration.ofSeconds(15);
    private List<String> tlsProfiles = List.of("chrome136", "safari184");
    public boolean isMihomoEnabled() { return mihomoEnabled; }
    public void setMihomoEnabled(boolean value) { mihomoEnabled = value; }
    public String getControllerUrl() { return controllerUrl; }
    public void setControllerUrl(String value) { controllerUrl = value; }
    public String getControllerSecret() { return controllerSecret; }
    public void setControllerSecret(String value) { controllerSecret = value; }
    public String getListenerHost() { return listenerHost; }
    public void setListenerHost(String value) { listenerHost = value; }
    public String getListenerBind() { return listenerBind; }
    public void setListenerBind(String value) { listenerBind = value; }
    public String getInterfaceName() { return interfaceName; }
    public void setInterfaceName(String value) { interfaceName = value; }
    public boolean isTlsEnabled() { return tlsEnabled; }
    public void setTlsEnabled(boolean value) { tlsEnabled = value; }
    public String getTlsUrl() { return tlsUrl; }
    public void setTlsUrl(String value) { tlsUrl = value; }
    public String getTlsSecret() { return tlsSecret; }
    public void setTlsSecret(String value) { tlsSecret = value; }
    public Duration getProbeTimeout() { return probeTimeout; }
    public void setProbeTimeout(Duration value) { probeTimeout = value; }
    public List<String> getTlsProfiles() { return tlsProfiles; }
    public void setTlsProfiles(List<String> value) { tlsProfiles = List.copyOf(value); }
}
