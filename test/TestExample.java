import edu.stevens.leansound.*;
import java.net.URL;
/**
 *
 * @author dkruger
 */
public class TestExample {
    public static void main(String[] args) throws Exception {
        ExamplePlayer p = new ExamplePlayer();//ExamplePlayer.loadFile("clips/bell.ogg");


        p.start();
        p.addSong("clips/bell.ogg");
        p.addSong("clips/shotgun.ogg");
        Thread.sleep(1000);
        p.stopCurrentSong();
        Thread.sleep(1000);
        p.continueCurrentSong();
        //p.clearSounds(); // remove all sounds from queue, leaving the current one playing
        // set a flag so that the next time the current player goes to get more data,it will stop playing this sound
    }
}
