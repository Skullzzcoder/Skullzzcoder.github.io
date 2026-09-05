import dev.skullzz.mirage.client.Tracker;

public class Harness {
    public static void main(String[] args) {
        for (String line : args) {
            Tracker.Payment p = Tracker.read(line, 0L);
            System.out.println(p == null ? "-" : (p.incoming ? "IN" : "OUT")
                    + " " + p.player + " " + p.cents);
        }
    }
}
