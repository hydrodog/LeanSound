import edu.stevens.leansound.*;
import java.net.URL;
/**
 *
 * @author dkruger
 */
public class TestExample {
    public static void main(String[] args) throws Exception {
        ExamplePlayer p = ExamplePlayer.loadFile("clips/bell.ogg");
        //p.addSound("clips/shotgun.ogg");
        p.start();
        //p.clearSounds(); // remove all sounds from queue, leaving the current one playing
        //p.stopCurrent(); // set a flag so that the next time the current player goes to get more data,it will stop playing this sound
    }
}
