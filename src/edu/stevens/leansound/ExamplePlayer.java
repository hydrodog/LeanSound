package edu.stevens.leansound;

/*
 * Cleaned up code from Jon Kristensen
 * @modified Dov Kruger
 * @modified Itay Bachar
 * This streamlined player lets the user add a sequence of sounds and then play them.
 * Sounds are added to a queue, either by themselves or with a delay afterward.
 */
import com.jcraft.jogg.*;
import com.jcraft.jorbis.*;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownServiceException;
import java.util.ArrayList;
import java.util.*;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * The <code>ExamplePlayer</code> thread class will simply download and play OGG
 * media. All you need to do is supply a valid URL as the first argument.
 *
 * @author Jon Kristensen
 * @version 1.0
 */
public class ExamplePlayer implements Runnable {
    // If you wish to debug this source, please set the variable below to true.

    private final boolean debugMode = true;

    /*
	 * URLConnection and InputStream objects so that we can open a connection to
	 * the media file.
     */
    private URLConnection urlConnection = null;
    private InputStream inputStream = null;

    /*
	 * We need a buffer, its size, a count to know how many bytes we have read
	 * and an index to keep track of where we are. This is standard networking
	 * stuff used with read().
     */
    byte[] buffer = null;
    int bufferSize = 2048;
    int count = 0;
    int index = 0;

    /*
	 * JOgg and JOrbis require fields for the converted buffer. This is a buffer
	 * that is modified in regards to the number of audio channels. Naturally,
	 * it will also need a size.
     */
    byte[] convertedBuffer;
    int convertedBufferSize;

    // The source data line onto which data can be written.
    private SourceDataLine outputLine = null;

    // A three-dimensional an array with PCM information. 
    private float[][][] pcmInfo;

    // The index for the PCM information.
    private int[] pcmIndex;

    // Here are the four required JOgg objects...
    private Packet joggPacket = new Packet();
    private Page joggPage = new Page();
    private StreamState joggStreamState = new StreamState();
    private SyncState joggSyncState = new SyncState();

    // ... followed by the four required JOrbis objects.
    private DspState jorbisDspState = new DspState();
    private Block jorbisBlock = new Block(jorbisDspState);
    private Comment jorbisComment = new Comment();
    private Info jorbisInfo = new Info();

    //Itay Addition:
    private static class Sound {
        public InputStream s;
        public int delay;
        public Sound(InputStream s, int delay) { this.s = s; this.delay = delay; }
        public void doDelay() {
             if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch(InterruptedException e) {
                }
             }
        }
    }
    private Queue<Sound> clips;
//    private ArrayList<InputStream> clips = new ArrayList();
    private boolean playing = true;

    /**
     * The programs <code>main()</code> method. Will read the first command-line
     * argument and use it as URL, after which it will start the thread.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        // Set the URL as the first argument, if any.
        String url = args.length > 0 ? url = args[0] : null;

        /*
		 * If the url variable is set, start the thread. If not, give an error
		 * and die.
         */
        if (url != null) {
            ExamplePlayer examplePlayer = new ExamplePlayer(url);
            examplePlayer.play();
        } else {
            System.err.println("Please provide an argument with file to play.");
        }
    }

    /**
     * The constructor; will configure the <code>InputStream</code>.
     *
     * @param pUrl the URL to be opened
     */
    public ExamplePlayer(String pUrl) {
        configureInputStream(getUrl(pUrl));
    }

    /**
     * Given a string, <code>getUrl()</code> will return an URL object.
     *
     * @param pUrl the URL to be opened
     * @return the URL object
     */
    public URL getUrl(String pUrl) {
        URL url = null;

        try {
            url = new URL(pUrl);
        } catch (MalformedURLException exception) {
            System.err.println("Malformed \"url\" parameter: \"" + pUrl + "\"");
        }

        return url;
    }

    public static ExamplePlayer loadFile(String filename) throws Exception {
        return new ExamplePlayer(new FileInputStream(filename));
    }

    private ExamplePlayer(InputStream is) {
        inputStream = is;
    }

    /**
     * Sets the <code>inputStream</code> object by taking an URL, opens a
     * connection to it and get the <code>InputStream</code>.
     *
     * @param pUrl the url to the media file
     */
    private void configureInputStream(URL pUrl) {
        // Try to open a connection to the URL.
        try {
            urlConnection = pUrl.openConnection();
        } catch (UnknownServiceException exception) {
            System.err.println("The protocol does not support input.");
        } catch (IOException exception) {
            System.err.println("An I/O error occoured while trying create the "
                    + "URL connection.");
        }

        // If we have a connection, try to create an input stream.
        if (urlConnection != null) {
            try {
                inputStream = urlConnection.getInputStream();
            } catch (IOException exception) {
                System.err
                        .println("An I/O error occoured while trying to get an "
                                + "input stream from the URL.");
                System.err.println(exception);
            }
        }
    }

    /**
     * This method is probably easiest understood by looking at the body.
     * However, it will - if no problems occur - call methods to initialize the
     * JOgg JOrbis libraries, read the header, initialize the sound system, read
     * the body of the stream and clean up.
     */
    public void run() {
        while (true) {
            synchronized(this) {
                /*
                while (clips.isEmpty()) {
                    try {
                        clips.wait(); // wait to be woken up when someone adds a new sound
                    } catch(InterruptedException e) {}
                }
                */
                Sound sound = clips.remove();
                if (sound == null)
                    continue;
                inputStream = sound.s;
                sound.doDelay();
            }

            // Initialize JOrbis.
            initializeJOrbis();

             /*
              * If the header is readable, try to inialize the sound system. If that
              * works, try to read the body.
              */
             if (readHeader()) {
                 if (initializeSound()) {
                    readBody();
                 }
             }

             // Afterwards, clean up.
             cleanUp();
        }
    }

    /**
     * Initializes JOrbis. First, we initialize the <code>SyncState</code>
     * object. After that, we prepare the <code>SyncState</code> buffer. Then we
     * "initialize" our buffer, taking the data in <code>SyncState</code>.
     */
    private void initializeJOrbis() {
        debugOutput("Initializing JOrbis.");

        // Initialize SyncState
        joggSyncState = new SyncState();
        //joggSyncState.init();

        // Prepare the to SyncState internal buffer
        joggSyncState.buffer(bufferSize);

        /*
		 * Fill the buffer with the data from SyncState's internal buffer. Note
		 * how the size of this new buffer is different from bufferSize.
         */
        buffer = joggSyncState.data;

        debugOutput("Done initializing JOrbis.");
    }

    /**
     * This method reads the header of the stream, which consists of three
     * packets.
     *
     * @return true if the header was successfully read, false otherwise
     */
    private boolean readHeader() {
        debugOutput("Starting to read the header.");

        /*
	* Variable used in loops below. While we need more data, we will
	* continue to read from the InputStream.
         */
        boolean needMoreData = true;

        /*
	* We will read the first three packets of the header. We start off by
	* defining packet = 1 and increment that value whenever we have
	* successfully read another packet.
         */
        int packet = 1;

        /*
		 * While we need more data (which we do until we have read the three
		 * header packets), this loop reads from the stream and has a big
		 * <code>switch</code> statement which does what it's supposed to do in
		 * regards to the current packet.
         */
        while (needMoreData) {
            // Read from the InputStream.
            try {
                count = inputStream.read(buffer, index, bufferSize);
            } catch (IOException exception) {
                System.err.println("Could not read from the input stream.");
                System.err.println(exception);
            }

            // We let SyncState know how many bytes we read.
            joggSyncState.wrote(count);

            /*
			 * We want to read the first three packets. For the first packet, we
			 * need to initialize the StreamState object and a couple of other
			 * things. For packet two and three, the procedure is the same: we
			 * take out a page, and then we take out the packet.
             */
            switch (packet) {
                // The first packet.
                case 1: {
                    // We take out a page.
                    switch (joggSyncState.pageout(joggPage)) {
                        // If there is a hole in the data, we must exit.
                        case -1: {
                            System.err.println("There is a hole in the first "
                                    + "packet data.");
                            return false;
                        }

                        // If we need more data, we break to get it.
                        case 0: {
                            break;
                        }

                        /*
                         * We got where we wanted. We have successfully read the
                         * first packet, and we will now initialize and reset
                         * StreamState, and initialize the Info and Comment
                         * objects. Afterwards we will check that the page
                         * doesn't contain any errors, that the packet doesn't
                         * contain any errors and that it's Vorbis data.
                         */
                        case 1: {
                            // Initializes and resets StreamState.
                            joggStreamState.init(joggPage.serialno());
                            joggStreamState.reset();

                            // Initializes the Info and Comment objects.
                            jorbisInfo.init();
                            jorbisComment.init();

                            // Check the page (serial number and stuff).
                            if (joggStreamState.pagein(joggPage) == -1) {
                                System.err.println("We got an error while "
                                        + "reading the first header page.");
                                return false;
                            }

                            /*
			 * Try to extract a packet. All other return values
			 * than "1" indicates there's something wrong.
                             */
                            if (joggStreamState.packetout(joggPacket) != 1) {
                                System.err.println("We got an error while "
                                        + "reading the first header packet.");
                                return false;
                            }

                            /*
               * Extract comments from the packet into the Info object
               * among other things. If this fails, it's not
               * Vorbis data.
                             */
                            if (jorbisInfo.synthesis_headerin(jorbisComment,
                                    joggPacket) < 0) {
                                System.err.println("We got an error while "
                                        + "interpreting the first packet. "
                                        + "Apparantly, it's not Vorbis data.");
                                return false;
                            }

                            // We're done here, let's increment "packet".
                            packet++;
                            break;
                        }
                    }

                    /*
					 * Note how we are NOT breaking here if we have proceeded to
					 * the second packet. We don't want to read from the input
					 * stream again if it's not necessary.
                     */
                    if (packet == 1) {
                        break;
                    }
                }

                // The code for the second and third packets follow.
                case 2:
                case 3: {
                    // Try to get a new page again.
                    switch (joggSyncState.pageout(joggPage)) {
                        // If there is a hole in the data, we must exit.
                        case -1: {
                            System.err.println("error:  hole in the packet data #" + packet);
                            return false;
                        }

                        // If we need more data, we break to get it.
                        case 0: {
                            break;
                        }

                        /*
             * Extract a packet from the page and
             * (if everything goes well) give the information to
	     * the Info and Comment objects as above.
                         */
                        case 1: {
                            // Share the page with the StreamState object.
                            joggStreamState.pagein(joggPage);

                            // Just like the switch(...packetout...) lines above. Why so repetitive?
                            switch (joggStreamState.packetout(joggPacket)) {
                                // If there is a hole in the data, we must exit.
                                case -1: {
                                    System.err.println("error: hole in the first packet data.");
                                    return false;
                                }

                                // If we need more data, we break to get it.
                                case 0: {
                                    break;
                                }

                                // process a packet
                                case 1: {
                                    // Give the packet to Info and Comment objects.
                                    jorbisInfo.synthesis_headerin(jorbisComment, joggPacket);

                                    // Increment packet.
                                    packet++;

                                    if (packet == 4) {
                                        // No fourth packet, so end the loop here.
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

            // We get the new index and an updated buffer.
            index = joggSyncState.buffer(bufferSize);
            buffer = joggSyncState.data;

            /*
			 * If we need more data but can't get it, the stream doesn't contain
			 * enough information.
             */
            if (count == 0 && needMoreData) {
                System.err.println("Not enough header data was supplied.");
                return false;
            }
        }

        debugOutput("Finished reading the header.");

        return true;
    }

    private final void readHeaderPacket1(InputStream clip) throws IOException {
        count = clip.read(buffer, index, bufferSize); // packet #1
        joggSyncState.wrote(count); // inform jogg how many bytes read so far
        switch (joggSyncState.pageout(joggPage)) {
             case -1: // If there is a hole in the data, we must exit.
                 throw new RuntimeException("hole in packet data #1");
             case 1: { // Initialize and resets StreamState.
                 joggStreamState.init(joggPage.serialno());
                 joggStreamState.reset();

                 // Initialize the Info and Comment objects.
                 jorbisInfo.init();
                 jorbisComment.init();

                 // Check the page (serial number and stuff).
                 if (joggStreamState.pagein(joggPage) == -1)
                     throw new RuntimeException("error reading header page #1");

                 // Try to extract a packet.
                 if (joggStreamState.packetout(joggPacket) != 1)
                     throw new RuntimeException("error reading header packet #1");
             }
             case 0:
                 System.out.println("Why does this happen? What does it mean to have return 0?");
        }        
    }
    
    private final void readHeaderPacket23(InputStream clip, int packet) throws IOException {
        count = clip.read(buffer, index, bufferSize); // packet #1
        joggSyncState.wrote(count); // inform jogg how many bytes read so far
         int status = joggSyncState.pageout(joggPage);
         if (status == -1)
             throw new RuntimeException("error:  hole in the packet data #" + packet);
         if (status == 1) {
             joggStreamState.pagein(joggPage);
             status = joggStreamState.packetout(joggPacket);
             if (status == -1)
                 throw new RuntimeException("error: hole in packet data #" + packet);
             if (status == 1)
                 jorbisInfo.synthesis_headerin(jorbisComment, joggPacket);   
         }
    }
    private void readClip() throws IOException {
        // first deal with header
        debugOutput("Reading the header");
//        InputStream clip = clips.get(songIndex);
        InputStream clip = inputStream;
        readHeaderPacket1(clip);     // each clip will throw an exception to get out
        readHeaderPacket23(clip, 2); // if necessary
        readHeaderPacket23(clip, 3);

        // Get the new index and an updated buffer.
        index = joggSyncState.buffer(bufferSize);
        buffer = joggSyncState.data;
        debugOutput("Reading the body.");

        boolean needMoreData = true;
        do {
            //Stop mid Sound if playing is false
            if (!playing)
                return; // early exit, termination  of clip requested by programmer
            if (joggSyncState.pageout(joggPage) == 1) {
                // Give the page to the StreamState object.
                joggStreamState.pagein(joggPage);

                if (joggPage.granulepos() == 0) // no more data is needed
                    return; // get out, clip is done
            }
            // Process each packets.
            processPackets:
            while (true) {
               switch(joggStreamState.packetout(joggPacket)) {
               case 1:
                   decodeCurrentPacket();
                   break;
               case 0:
                   break processPackets;
               case -1:
                   debugOutput("hole in packet, continuing");
               }

               if (joggPage.eos() != 0) { // If page is the end-of-stream, done
                   needMoreData = false;
               }
            }
 
            // If we need more data...
            if (needMoreData) {
                // Get the new index and an updated buffer.
                index = joggSyncState.buffer(bufferSize);
                buffer = joggSyncState.data;
                count = clip.read(buffer, index, bufferSize);
                joggSyncState.wrote(count);  // Let SyncState know #bytes read.

                if (count == 0) // There's no more data in the stream.
                   needMoreData = false;
            } else
                debugOutput("waiting to resume");
        } while (needMoreData);
        debugOutput("Done reading the body.");
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
    private boolean initializeSound() {
        debugOutput("Initializing the sound system.");

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
        AudioFormat audioFormat = new AudioFormat((float) rate, 16, channels,
                true, false);
        DataLine.Info datalineInfo = new DataLine.Info(SourceDataLine.class,
                audioFormat, AudioSystem.NOT_SPECIFIED);

        // Check if the line is supported.
        if (!AudioSystem.isLineSupported(datalineInfo)) {
            System.err.println("Audio output line is not supported.");
            return false;
        }

        /*
		 * Everything seems to be alright. Let's try to open a line with the
		 * specified format and start the source data line.
         */
        try {
            outputLine = (SourceDataLine) AudioSystem.getLine(datalineInfo);
            outputLine.open(audioFormat);
        } catch (LineUnavailableException exception) {
            System.out.println("The audio output line could not be opened due "
                    + "to resource restrictions.");
            System.err.println(exception);
            return false;
        } catch (IllegalStateException exception) {
            System.out.println("The audio output line is already open.");
            System.err.println(exception);
            return false;
        } catch (SecurityException exception) {
            System.out.println("The audio output line could not be opened due "
                    + "to security restrictions.");
            System.err.println(exception);
            return false;
        }

        // Start it.
        outputLine.start();

        /*
		 * We create the PCM variables. The index is an array with the same
		 * length as the number of audio channels.
         */
        pcmInfo = new float[1][][];
        pcmIndex = new int[jorbisInfo.channels];

        debugOutput("Done initializing the sound system.");

        return true;
    }

    /**
     * This method reads the entire stream body. Whenever it extracts a packet,
     * it will decode it by calling <code>decodeCurrentPacket()</code>.
     */
    private void readBody() {
        debugOutput("Reading the body.");

        /*
		 * Variable used in loops below, like in readHeader(). While we need
		 * more data, we will continue to read from the InputStream.
         */
        boolean needMoreData = true;

        while (needMoreData) {
            //Stop mid Sound if playing is false
            if (playing) {
                switch (joggSyncState.pageout(joggPage)) {
                    // If there is a hole in the data, we just proceed.
                    case -1: {
                        debugOutput("There is a hole in the data. We proceed.");
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
                                    debugOutput("There is a hole in the data, we "
                                            + "continue though.");
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

                // If we need more data...
                if (needMoreData) {
                    // We get the new index and an updated buffer.
                    index = joggSyncState.buffer(bufferSize);
                    buffer = joggSyncState.data;

                    // Read from the InputStream.
                    try {
                        count = inputStream.read(buffer, index, bufferSize);
                    } catch (Exception e) {
                        System.err.println(e);
                        return;
                    }

                    // We let SyncState know how many bytes we read.
                    joggSyncState.wrote(count);

                    // There's no more data in the stream.
                    if (count == 0) {
                        needMoreData = false;
                    }
                }
            } else debugOutput("waiting to resume");

        }
        debugOutput("Done reading the body.");
    }

    /**
     * A clean-up method, called when everything is finished. Clears the
     * JOgg/JOrbis objects and closes the <code>InputStream</code>.
     */
    private void cleanUp() {
        debugOutput("Cleaning up.");

        // Clear the necessary JOgg/JOrbis objects.
        joggStreamState.clear();
        jorbisBlock.clear();
        jorbisDspState.clear();
        jorbisInfo.clear();
        joggSyncState.clear();

        //Itay Addition
        index = 0;
        count = 0;

        // Closes the stream.
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (Exception e) {
        }
        
        debugOutput("Done cleaning up.");
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

        // We need to know how many samples to process.
        int range;

        /*
		 * Get the PCM information and count the samples. And while these
		 * samples are more than zero...
         */
        while ((samples = jorbisDspState.synthesis_pcmout(pcmInfo, pcmIndex))
                > 0) {
            // We need to know for how many samples we are going to process.
            if (samples < convertedBufferSize) {
                range = samples;
            } else {
                range = convertedBufferSize;
            }

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
					 * We make sure our value doesn't exceed or falls below
					 * +-32767.
                     */
                    if (value > 32767) {
                        value = 32767;
                    }
                    if (value < -32768) {
                        value = -32768;
                    }

                    /*
					 * It the value is less than zero, we bitwise-or it with
					 * 32768 (which is 1000000000000000 = 10^15).
                     */
                    if (value < 0) {
                        value = value | 32768;
                    }

                    /*
					 * Take our value and split it into two, one with the last
					 * byte and one with the first byte.
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
            outputLine.write(convertedBuffer, 0, 2 * jorbisInfo.channels
                    * range);

            // Update the DspState object.
            jorbisDspState.synthesis_read(range);
        }
    }

    /**
     * This method is being called internally to output debug information
     * whenever that is wanted.
     *
     * @param output the debug output information
     */
    private void debugOutput(String output) {
        if (debugMode) {
            System.out.println("Debug: " + output);
        }
    }

    //Itay's Additions
    public ExamplePlayer() {
        clips = new LinkedList<>();
    }

    public synchronized void addClip(String filename) {
        try {
            clips.add(new Sound(new FileInputStream(filename), 30));
        } catch (FileNotFoundException e) {
            System.err.println("Could not find file!");
        }
    }

    public synchronized void clearQueue() {
        clips.clear();
    }

    public void stopCurrentSong() {
        playing = false;
        debugOutput("Pausing Song");
    }
    
    public void continueCurrentSong(){
        playing = true;
        debugOutput("Resuming Song");

    }
    public void play() {
        Thread t = new Thread(this);
        t.start(); // because java threads are annoying and once terminated must be thrown away
    }

}
