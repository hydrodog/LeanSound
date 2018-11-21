import edu.stevens.leansound.LoadSounds;
/**
 *
 * @author dkruger
 */
public class TestLoadSounds {
    public static void main(String[] args) throws Exception {
        LoadSounds sounds = new LoadSounds("audio.db", "clips");
        sounds.save();
        
    }
}
