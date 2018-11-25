import java.util.*;
import edu.stevens.leansound.*;
/**
 *
 * @author dkruger
 */
public class SayNumber {
    public static void main(String[] args) {
        Scanner s = new Scanner(System.in);
        final String clip = "clips/";
        final String[] tensNames = {"ten", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        final String[] teens = {
            "eleven", "twelve", "thirteen", 
            "fourteen", "fifteen", "sixteen",
            "seventeen","eighteen", "nineteen"
        };
        ExamplePlayer p = new ExamplePlayer();
        int n = s.nextInt();
        if (n > 999) {
            int thousand = n / 1000;
            p.addClip(clip + thousand + ".ogg");
            p.addClip(clip + "thousand.ogg");
            n = n % 1000;
        }
        if (n > 100) {
            int hundred = n / 100;
            p.addClip(clip + hundred + ".ogg");
            p.addClip(clip + "hundred.ogg");
            n = n % 100;
        }
        if (n > 10) {
            if (n < 20) {
                p.addClip(clip + n + ".ogg");
            } else {
                int tens = n / 10;
                p.addClip(clip + tens*10 + ".ogg");
                p.addClip(clip + n % 10 + ".ogg");
            }
        }
        p.play();
    }
}
