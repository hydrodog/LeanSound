package edu.stevens.leansound;

/**
 * @author Dov Kruger Cleaned up code from Jon Kristensen, see
 * http://www.jcraft.com/jorbis/
 * 
 * This object can load up a directory at a time of sound clips and
 * load into a single block of memory.
 * This block can be loaded very fast with a single read
 * It can bind a name and an offset to rapidly find each clip
 * Assuming all the files are the same encoding, it would be nice to remove 
 * all header information and have only a single header for the entire library
 * 
 * Usage:
 * LoadSounds sounds("sounds.sdb", "dir"); // names mapped to file names (without ogg)
 * sounds.add("shot", "gunshot.ogg"); // manually add a sound and a name
 * sounds.save(); // write out the sounds
 * 
 * At runtime in a game
 * LoadSounds sounds = LoadSounds.fastLoad("sounds.sdb");
 * sounds.play("gunshot"); // play until complete
 * sounds.play("bachpreludes");
 * 
 * If a sound is long (like a music soundtrack) I would like to be able to stop it.
 * The original code is a thread, which is not great.  I made this one Runnable, also not great.
 * Instead I would suggest we create a pool of threads and somehow identify which one should be used.
 * perhaps:
 * 
 * sounds.stopPlaying(BACKGROUND_MUSIC);
 * sounds.play(BACKGROUND_MUSIC, "punktheme"); // start new music going
 * 
 * the default should be that clips added to a thread get added onto a queue
 */

import com.jcraft.jogg.*;
import com.jcraft.jorbis.*;
import java.io.*;
import javax.sound.sampled.*;
import java.util.*;

/*
	Load sounds into RAM so they can be played quickly without I/O
 */
public class LoadSounds implements Runnable {
    // for efficiency, store the sampled bytes from all files in one huge byte array

    private byte[] sounds;
    // index by name into the array to find each file
    private HashMap<String, Integer> offsets;
    private String audioDBName;
    int cursor; // position within sounds
    //TODO: we need one cursor for each thread.  Perhaps create sounds in one class
    // and the players can be in another class

    // files are completely read into RAM so sounds can be played quickly
    private byte[] convertedBuffer; // decompressed sound buffer for playing
    private int convertedBufferSize; // in case smaller than convertedBuffer.length

    /*
	 * JOgg and JOrbis require fields for the converted buffer. This is a buffer
	 * that is modified in regards to the number of audio channels. Naturally,
	 * it will also need a size.
     */
    // The source data line onto which data can be written.
    private SourceDataLine outputLine;

    // A three-dimensional an array with PCM information. 
    private float[][][] pcmInfo;

    // The index for the PCM information.
    private int[] pcmIndex;

    // Here are the four required JOgg objects...
    private byte[] buffer;
    private int bufferSize; // TODO: buffers should be local to each thread
    private Packet joggPacket;
    private Page joggPage;
    private StreamState joggStreamState;
    private SyncState joggSyncState;

    // ... followed by the four required JOrbis objects.
    private DspState jorbisDspState;
    private Block jorbisBlock;
    private Comment jorbisComment;
    private Info jorbisInfo;
    
    /* 3 kindsof sounds, each assigned their own thread */
    public static final int SOUNDEFFECT = 0;
    public static final int MUSIC = 1;
    public static final int BACKGROUND = 2;
    /**
     * Load all files in this directory in as sound clips Preallocate an
     * internal buffer big enough for the biggest one
     *
     */
    public LoadSounds(String audioDBName, String dirName) {
        outputLine = null;
        this.audioDBName = audioDBName;
        joggPacket = new Packet();
        joggPage = new Page();
        joggStreamState = new StreamState();
        joggSyncState = new SyncState();

        jorbisDspState = new DspState();
        jorbisBlock = new Block(jorbisDspState);
        jorbisComment = new Comment();
        jorbisInfo = new Info();
        initializeJOrbis(); // initialize the library once
        offsets = new HashMap<>(64);
        bufferSize = 2048;
        buffer = new byte[bufferSize];
        load(dirName);
    }

    public void finalize() {
        joggStreamState.clear();
        jorbisBlock.clear();
        jorbisDspState.clear();
        jorbisInfo.clear();
        joggSyncState.clear();
    }

    /**
     * Load up all the ogg files in one directory one by one to do this faster,
     * save this objects as a single database and then load
     */
    public void load(String dirName) {
        File dir = new File(dirName);
        if (!dir.isDirectory()) {
            throw new RuntimeException("Not a directory");
        }

        File[] files = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String pathname) {
                return pathname.endsWith(".ogg");
            }
        });
        int totalSize = 0;
        for (File f : files) {
            totalSize += f.length();
        }
        sounds = new byte[totalSize];
        int offset = 0;
        for (int i = 0; i < files.length; offset += files[i].length(), i++) {
            try {
                load(files[i], offset);
            } catch(Exception e) {
                System.err.println("Failed to load" + files[i].getName());
                e.printStackTrace();
            }
        }
    }

    // Save the big block of sounds to the database file
    public void save() throws Exception {
        FileOutputStream fos = new FileOutputStream(audioDBName);
        //TODO: write out offsets into this file of each separate audio clip
        //TODO: write out the name of each file
        //TODO: do this in a way that is not serializable since that is not portable and it's deprecated

        fos.write(sounds);
        fos.close();
    }

    /**
     * Load a single file into RAM in the sounds array
     */
    private void load(File f, int offset) throws Exception {
        long size = f.length();
        FileInputStream fis = new FileInputStream(f);
        /*
             * Verify the header, we try to inialize the sound system.
         */
        if (readHeader()) {
            if (initializeSound()) {
                readBody();
            }
        }
    }

    public void run() {
    }

    /**
     * Initializes JOrbis. 1. Initialize the <code>SyncState</code> object. 2.
     * Prepare the <code>SyncState</code> buffer. 3. initialize the buffer,
     * taking the data in <code>SyncState</code>.
     */
    //TODO: Look this up, hopefully we do this just once, not once per music play
    private void initializeJOrbis() {
        //	debugOutput("Initializing JOrbis.");
        joggSyncState.init();		          // Initialize SyncState
        joggSyncState.buffer(bufferSize); // Prepare SyncState internal buffer
        buffer = joggSyncState.data; // fill the buffer from SyncState's internal buffer.
        //		debugOutput("Done initializing JOrbis.");
    }

    /**
     * This method reads the header of the stream, which consists of three
     * packets.
     *
     * @return true if the header was successfully read, false otherwise
     */
    private boolean readHeader() {
        //TODO: it's nice to have debugoutput, but ludicrous to put it in this class.
        //TODO: if we need one, put it in a utility class so it is shared.
        //debugOutput("Starting to read the header.");

        boolean needMoreData = true;

        /*
		 * Start by defining packet = 1 and increment that value whenever 
		 * a packet is successfully read, total should be 3
         */
        int packet = 1;
        int cursor = 0;
        int index = 0;
        int remaining = sounds.length; // TODO: this needs to be just one sample!
        
        //TODO: look at this ridiculous code with the switch statements.  What a mess
        // Just get it working with this before we do surgery and streamline
        while (needMoreData) {	// Read from the InputStream.
            int count = remaining > bufferSize ? bufferSize : remaining;
            System.arraycopy(sounds, cursor, buffer, index, bufferSize);
            //TODO: instead of copying from the array, use the array directly, but
            // only if ogg code does not change the buffer.  We must be able to play it again
            cursor += count;
            joggSyncState.wrote(count); // tell SyncState how many bytes read

            /*
             * Read the first three packets. For the first packet, we
             * need to initialize the StreamState object and a couple of other
             * things. For packet two and three, the procedure is the same: we
             * take out a page, and then we take out the packet.
             */
            switch (packet) {
                case 1: {	// The first packet.  TODO: Terrible style. can this be fixed?
                    switch (joggSyncState.pageout(joggPage)) {	// take out a page.
                        case -1: { // If there is a hole in the data, exit
                            throw new RuntimeException("hole in packet #1");
                        }
                        case 0: {	// If more data needed, break to get it.
                            break;
                        }

                       /*
                        * Successfully read packet #1, now initialize and reset
                        * StreamState, and initialize the Info and Comment
                        * objects. Afterwards check that the page does not
                        * contain any errors, that the packet doesn't
                        * contain any errors and that it's Vorbis data.
                        */
                        case 1: {
                            // Initializes and resets StreamState.
                            joggStreamState.init(joggPage.serialno());
                            joggStreamState.reset();

                            jorbisInfo.init();	 // Initializes the Info and Comment objects.
                            jorbisComment.init();

                            // Check the page (serial number and stuff).
                            if (joggStreamState.pagein(joggPage) == -1) {
                                throw new RuntimeException("Error reading header page #1");
                            }

                            /*
					 * Try to extract a packet. All other return values
					 * than "1" indicates there's something wrong.
                             */
                            if (joggStreamState.packetout(joggPacket) != 1) {
                                throw new RuntimeException("Error reading header packet #1");
                            }

                            /*
					 * We give the packet to the Info object, so that it
					 * can extract the Comment-related information,
					 * among other things. If this fails, it's not
					 * Vorbis data.
                             */
                            if (jorbisInfo.synthesis_headerin(jorbisComment, joggPacket) < 0) {
                                throw new RuntimeException("packet #1 is not vorbis data");
                            }

                            // We're done here, let's increment "packet".
                            packet++;
                            break;
                        }
                    }

                    if (packet == 1) {
                        break;
                    }
                }

                // The code for the second and third packets follow.
                case 2:
                case 3: {
                    switch (joggSyncState.pageout(joggPage)) { // get a new page again
                        // If there is a hole in the data, we must exit.
                        case -1: {
                            throw new RuntimeException("hole in packet #" + packet);
                        }

                        // If we need more data, we break to get it.
                        case 0: {
                            break;
                        }

                        case 1: { // extract a packet and give to info and comment objects
                            // Share the page with the StreamState object.
                            joggStreamState.pagein(joggPage);

                            /*
					 * Just like the switch(...packetout...) lines
					 * above.
                             */
                            switch (joggStreamState.packetout(joggPacket)) {
                                case -1: { // If there is a hole in the data, we must exit.
                                    throw new RuntimeException("hole in packet #1");
                                }

                                case 0: {	// break to get more data
                                    break;
                                }

                                // We got a packet, let's process it.
                                case 1: { // give packet to info and comment objects
                                    jorbisInfo.synthesis_headerin(jorbisComment, joggPacket);
                                    packet++;									// Increment packet.
                                    if (packet == 4) {//end TODO: This logic SUCKS
                                        needMoreData = false;
                                    }

                                    break;
                                }
                            }

                            break;
                        }
                    }

                    break;
                }
            }

            // Get the new index and an updated buffer.
            index = joggSyncState.buffer(bufferSize);
            buffer = joggSyncState.data;

            /*
			 * If we need more data but can't get it, the stream doesn't contain
			 * enough information.
             */
            if (count == 0 && needMoreData) {
                throw new RuntimeException("Missing header data");
            }
        }

//        debugOutput("Finished reading the header.");

        return true;
    }

    /**
     * This method starts the sound system. It starts with initializing the
     * <code>DspState</code> object, after which it sets up the
     * <code>Block</code> object. Last but not least, it opens a line to the
     * source data line.
     *
     * @return true if the sound system was successfully started, false
     * otherwise
     */
    private boolean initializeSound() throws Exception {
        // This buffer is used by the decoding method.
        convertedBufferSize = bufferSize * 2;
        convertedBuffer = new byte[convertedBufferSize];

        // Initializes the DSP synthesis.
        jorbisDspState.synthesis_init(jorbisInfo);

        // Make the Block object aware of the DSP.
        jorbisBlock.init(jorbisDspState);

        // Wee need to know the channels and rate.
        int channels = jorbisInfo.channels;
        int rate = jorbisInfo.rate;

        // Creates an AudioFormat object and a DataLine.Info object.
        AudioFormat audioFormat = new AudioFormat((float)rate, 16, channels, 
                true, false);
        DataLine.Info datalineInfo = new DataLine.Info(SourceDataLine.class,
                audioFormat, AudioSystem.NOT_SPECIFIED);

        // Check if the line is supported.
        if (!AudioSystem.isLineSupported(datalineInfo)) {
            throw new RuntimeException("Audio output line is not supported.");
        }

        /*
         * Try to open a line with the specified format, start source data line
         */
        outputLine = (SourceDataLine) AudioSystem.getLine(datalineInfo);
        outputLine.open(audioFormat);
        outputLine.start();

        /*
		 * Create the PCM variables. The index is an array with the same
		 * length as the number of audio channels.
         */
        pcmInfo = new float[1][][];
        pcmIndex = new int[jorbisInfo.channels];

        //debugOutput("Done initializing the sound system.");

        return true;
    }

    /**
     * This method reads the entire stream body. Whenever it extracts a packet,
     * it will decode it by calling <code>decodeCurrentPacket()</code>.
     */
    private void readBody() {
        //debugOutput("Reading the body.");
        /*
	 * Variable used in loops below, like in readHeader(). While we need
	 * more data, we will continue to read from the InputStream.
         */
        boolean needMoreData = true;

        int remaining = bufferSize;
        int count = remaining >= bufferSize ? bufferSize : remaining;
        do {
            switch (joggSyncState.pageout(joggPage)) {
                // If there is a hole in the data, we just proceed.
                case -1: {
                    //debugOutput("There is a hole in the data. We proceed.");
                }

                // If we need more data, we break to get it.
                case 0: {
                    break;
                }

                // If we have successfully checked out a page, we continue.
                case 1: {
                    // Give the page to the StreamState object.
                    joggStreamState.pagein(joggPage);

                    // If granulepos() returns "0", we don't need more data.
                    if (joggPage.granulepos() == 0) {
                        needMoreData = false;
                        break;
                    }

                    // Here is where we process the packets.
                    processPackets:
                    while (true) {
                        switch (joggStreamState.packetout(joggPacket)) {
                            // Is it a hole in the data?
                            case -1: {
                           //     debugOutput("error: hole in data, continuing");
                            }

                            // If we need more data, we break to get it.
                            case 0: {
                                break processPackets;
                            }

                            /*
			 * If we have the data we need, we decode the
			 * packet.
                             */
                            case 1: {
                                decodeCurrentPacket();
                            }
                        }
                    }

                    /*
                    * If the page is the end-of-stream, we don't need more
                     * data.
                     */
                    if (joggPage.eos() != 0) {
                        needMoreData = false;
                    }
                }
            }

            // TODO: This code is just dreadful.
            if (needMoreData) {
                // We get the new index and an updated buffer.
                int index = joggSyncState.buffer(bufferSize);
                buffer = joggSyncState.data;
                //TODO: compute remaining correctly
                count = remaining >= bufferSize ? bufferSize : remaining;
                System.arraycopy(sounds, cursor, buffer, index, count);
                joggSyncState.wrote(count);// Let SyncState know how many bytes read.
            }
        } while (count > 0);
        //debugOutput("Done reading the body.");
    }

    /**
     * Decodes the current packet and sends it to the audio output line.
     */
    private void decodeCurrentPacket() {
        int samples;

        // Check that the packet is a audio data packet etc.
        if (jorbisBlock.synthesis(joggPacket) == 0) {
            // Give the block to the DspState object.
            jorbisDspState.synthesis_blockin(jorbisBlock);
        }

        // Determine the number of samples to process.
        int range;

        /*
	 * Get the PCM information and count the samples. And while these
	 * samples are more than zero...
         */
        while ((samples = jorbisDspState.synthesis_pcmout(pcmInfo, pcmIndex)) > 0) {
            // We need to know for how many samples we are going to process.
            range = samples < convertedBufferSize ? samples : convertedBufferSize;

            // For each channel...
            for (int i = 0; i < jorbisInfo.channels; i++) {
                int sampleIndex = i * 2;
                // For every sample in our range...
                for (int j = 0; j < range; j++) {
                    /*
		 * Get the PCM value for the channel at the correct
		 * position.
                     */
                    int value = (int) (pcmInfo[0][i][pcmIndex[i] + j] * 32767);

                    /*
		 * clamp the value to the range [-32767, +32767]
                     */
                    if (value > 32767) {
                        value = 32767;
                    }
                    if (value < -32768) {
                        value = -32768;
                    }

                    /*
		 * If the value is less than zero, OR it with
		 * 32768 (which is 1000000000000000 = 10^15).
                     */
                    if (value < 0) {
                        value = value | 32768;
                    }

                    /*
		 * Split the integer into two, low-byte, high-byte
                     */
                    convertedBuffer[sampleIndex] = (byte) (value);
                    convertedBuffer[sampleIndex + 1] = (byte) (value >>> 8);

                    /*
		 * Move the sample index forward by two (since that's how
		 * many values we get at once) times the number of channels.
                     */
                    sampleIndex += 2 * (jorbisInfo.channels);
                }
            }

            // Write the buffer to the audio output line.
            outputLine.write(convertedBuffer, 0, 2 * jorbisInfo.channels * range);

            // Update the DspState object.
            jorbisDspState.synthesis_read(range);
        }
    }

    public void play(int whichThread, String sound) {
    }

    /*
     * stop the playing of music in a single thread
     */
    public void stop(int whichThread) {
    }
}
