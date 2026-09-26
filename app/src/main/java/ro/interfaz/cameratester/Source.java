package ro.interfaz.cameratester;

import org.json.*;
import java.util.regex.Pattern;

final class Source {
    private static final Pattern CONTROL_NODE=Pattern.compile("(?<![a-z0-9_-])(?:cam[-_\\s]+req[-_\\s]+mgr|cam[-_\\s]+sync|dummy[-_\\s]+video[-_\\s]+device)(?![a-z0-9_-])",Pattern.CASE_INSENSITIVE);
    static final String[] SLOTS = {"ADAS Front", "ADAS Rear", "360 Front", "360 Left", "360 Right", "360 Rear"};
    final String key, kind, address, physical, label;
    String evidence, lastResult = "Discovered · not tested";
    long discoveredAt = System.currentTimeMillis(), lastSeenAt = discoveredAt, verifiedAt;
    int width, height;
    boolean present = true;

    Source(String kind, String address, String physical, String label, String evidence) {
        this.kind=kind; this.address=address; this.physical=physical; this.label=label; this.evidence=evidence;
        this.key=kind+":"+address+(physical.isEmpty()?"":"#"+physical);
    }
    static boolean isControlNode(String text) { return text!=null && CONTROL_NODE.matcher(text).find(); }
    boolean playable() { return kind.equals("camera2") || kind.equals("legacy") || kind.equals("uvc") || kind.equals("network") || kind.equals("teyes") || (kind.equals("v4l2") && !isControlNode(evidence) && !isControlNode(label)); }
    JSONObject json() throws JSONException {
        return new JSONObject().put("key",key).put("kind",kind).put("address",address).put("physical",physical)
            .put("label",label).put("evidence",evidence).put("lastResult",lastResult).put("discoveredAt",discoveredAt)
            .put("lastSeenAt",lastSeenAt).put("verifiedAt",verifiedAt).put("present",present).put("width",width).put("height",height);
    }
    static Source from(JSONObject j) {
        Source s=new Source(j.optString("kind"),j.optString("address"),j.optString("physical"),j.optString("label"),j.optString("evidence"));
        s.lastResult=j.optString("lastResult"); s.discoveredAt=j.optLong("discoveredAt"); s.lastSeenAt=j.optLong("lastSeenAt");
        s.verifiedAt=j.optLong("verifiedAt"); s.present=j.optBoolean("present"); s.width=j.optInt("width"); s.height=j.optInt("height"); return s;
    }
}
