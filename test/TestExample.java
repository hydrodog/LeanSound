import edu.stevens.leansound.*;
import java.net.URL;
/**
 *
 * @author dkruger
 */
public class TestExample {
    public static void main(String[] args) throws Exception {
        ExamplePlayer examplePlayer = ExamplePlayer.loadFile("clips/bell.ogg");
        examplePlayer.start();
    }
}
