package git.artdeell.util;

public class StringUtil {

    public static String unxorify(int[] buffer, int key){
        StringBuilder s = new StringBuilder();
        for (int i : buffer) {
            s.append((char) (i ^ key));
        }
        return s.toString();
    }
}
