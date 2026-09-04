import dev.skullzz.mirage.client.Litematic;
import dev.skullzz.mirage.client.Nbt;
import java.nio.file.Paths;

public class Harness {
    public static void main(String[] args) throws Exception {
        for (String file : args) {
            try {
                Litematic lit = Litematic.parse(Nbt.read(Paths.get(file)), 500000);
                StringBuilder out = new StringBuilder();
                out.append(file).append(" OK name=").append(lit.name)
                   .append(" size=").append(lit.size())
                   .append(" regions=").append(lit.regions)
                   .append(" palette=").append(lit.palette.size())
                   .append(" blocks=").append(lit.count()).append("\n");
                for (int i = 0; i < lit.blocks.length; i += 4) {
                    Litematic.Entry e = lit.palette.get(lit.blocks[i + 3]);
                    out.append("   ").append(lit.blocks[i]).append(',')
                       .append(lit.blocks[i + 1]).append(',').append(lit.blocks[i + 2])
                       .append(' ').append(e.name);
                    if (!e.properties.isEmpty()) out.append(e.properties);
                    out.append('\n');
                }
                System.out.print(out);
            } catch (Exception failure) {
                System.out.println(file + " REFUSED " + failure.getMessage());
            }
        }
    }
}
