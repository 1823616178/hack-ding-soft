import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.iface.reference.StringReference;

public final class ExtractSm2Config {
    private static final String TARGET_CLASS = "Lcom/hanwang/facekey/main/minaSocketTcp/TCPClientHandler;";
    private static final String OWNER_ID = "汉王人脸仪客户端";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: ExtractSm2Config <classes2.dex> <output.json>");
        }
        DexFile dexFile = DexFileFactory.loadDexFile(args[0], null);
        List<String> strings = constructorStrings(dexFile);
        List<String> keys = new ArrayList<>();
        for (String value : strings) {
            if (value.matches("(?i)^[0-9a-f]{64}$")) {
                keys.add(value.toUpperCase());
            }
        }
        if (!strings.contains(OWNER_ID) || keys.size() != 3) {
            throw new IllegalStateException("Expected owner id and exactly three 64-hex SM2 values");
        }

        String privateKey = keys.get(0);
        String publicKeyX = keys.get(1);
        String publicKeyY = keys.get(2);
        validatePair(privateKey, publicKeyX, publicKeyY);
        String output = "{\n"
                + "  \"ownerId\": \"" + jsonEscape(OWNER_ID) + "\",\n"
                + "  \"curve\": \"sm2p256v1\",\n"
                + "  \"privateKey\": \"" + privateKey + "\",\n"
                + "  \"publicKeyX\": \"" + publicKeyX + "\",\n"
                + "  \"publicKeyY\": \"" + publicKeyY + "\",\n"
                + "  \"uppercase\": true\n"
                + "}\n";
        Path outputPath = Path.of(args[1]);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        Files.writeString(outputPath, output, StandardCharsets.UTF_8);
        System.out.printf("ownerIdLength=%d privateKeyLength=%d publicKeyXLength=%d publicKeyYLength=%d privateKeySha256=%s%n",
                OWNER_ID.length(), privateKey.length(), publicKeyX.length(), publicKeyY.length(), sha256(privateKey));
    }

    private static List<String> constructorStrings(DexFile dexFile) {
        for (ClassDef classDef : dexFile.getClasses()) {
            if (!TARGET_CLASS.equals(classDef.getType())) continue;
            for (Method method : classDef.getMethods()) {
                if (!"<init>".equals(method.getName())
                        || method.getParameterTypes().size() != 1
                        || !"Landroid/os/Handler;".equals(method.getParameterTypes().get(0))) {
                    continue;
                }
                List<String> result = new ArrayList<>();
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (!(instruction instanceof ReferenceInstruction)) continue;
                    Reference reference = ((ReferenceInstruction) instruction).getReference();
                    if (reference instanceof StringReference) {
                        result.add(((StringReference) reference).getString());
                    }
                }
                return result;
            }
        }
        throw new IllegalStateException("TCPClientHandler.<init>(Handler) not found");
    }

    private static void validatePair(String privateKey, String publicKeyX, String publicKeyY) {
        if (new BigInteger(privateKey, 16).signum() <= 0) throw new IllegalStateException("Invalid private key");
        if (new BigInteger(publicKeyX, 16).signum() <= 0) throw new IllegalStateException("Invalid public X");
        if (new BigInteger(publicKeyY, 16).signum() <= 0) throw new IllegalStateException("Invalid public Y");
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest) result.append(String.format("%02x", b));
        return result.toString();
    }

    private static String jsonEscape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"' || ch == '\\') result.append('\\').append(ch);
            else if (ch < 0x20) result.append(String.format("\\u%04x", (int) ch));
            else result.append(ch);
        }
        return result.toString();
    }
}
