package network;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;

import javax.imageio.ImageIO;

import pImage.ImageSpectrum;
import pImage.PImage;
import pImage.Scanner;
import filter.AutoBalance;
import filter.BlurFilter;
import filter.HDREqualizer;
import filter.ImageFunction;
import gAPainter.Painter;

/**
 * R�alise le traitement d'une image.
 * 
 * @author Pierre-Alexandre
 *
 */
public class ComputationThread extends Thread {

	Socket socket;

	ComputationThread(Socket socket) {
		this.socket = socket;
	}

	@Override
	public void run() {
		final DataInputStream fromClient;
		final DataOutputStream toClient;
		
		try {
			System.out.println("computationThread: pr�t � traiter");

			fromClient = new DataInputStream(socket.getInputStream());
			toClient = new DataOutputStream(socket.getOutputStream());

			try {
				Result.sendDataToStream(null, 0, 0, toClient);
			} catch (IOException e) {
				e.printStackTrace();
			}

			final BufferedImage b= ImageIO.read(fromClient);
			if(b==null) {													//pour les tests de connection
				System.out.println("computationThread: test connection OK");
				return; 
			}
			PImage input= new PImage(b);

			PImage output= new PImage(0,0);	
			fromClient.skip(16); //on saute deux octets qui ne servent � rien

			ImageFunction function= ImageFunction.fromName(fromClient.readUTF());
			final int imageNumber= fromClient.readInt();	

			switch(function){
			case AUTO_BALANCE:
				int nbThreads= fromClient.readInt();
				int type= fromClient.readInt();
				int blurSize= fromClient.readInt();

				switch(type){
				case 0: output= AutoBalance.balance(input, nbThreads);     break;
				case 1: output= AutoBalance.balanceColors(input);		   break;	
				case 2: output= AutoBalance.balanceColors(input,blurSize); break;
				}
				break;

			case BLUR:
				int blurSize2= fromClient.readInt();

				output= BlurFilter.blur(input,blurSize2);
				break;

			case HDR_EQUALIZER:
				int algorithm= fromClient.readInt();
				int blurSize3= fromClient.readInt();

				if(algorithm==0) output= HDREqualizer.filter(input,blurSize3);
				else output= HDREqualizer.filter2(input,blurSize3, toClient);

				output= AutoBalance.balanceColors(output);

				break;

			case FOURIER_TRANSFORM:
				int isHighPassFilterOn= fromClient.readInt();
				int isScalingLog= fromClient.readInt();
				int cutFrequency= fromClient.readInt();

				BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
				Graphics2D g = source.createGraphics();
				g.drawImage(b, 0, 0, 64, 64, null);
				g.dispose();
				System.out.println("image loaded");
				//makes it a PImage object
				PImage img = new PImage(source);
				System.out.println("image converted to PImage");
				//does a Fourier Transform to create the image's spectrum
				ImageSpectrum spectrum = new ImageSpectrum(img);
				System.out.println("spectrum computed");

				if(isHighPassFilterOn==0){
					output= spectrum.getTransform(isScalingLog==1);
				} else{	
					spectrum.highPassFilter(cutFrequency); // the width of the part we remove in the spectrum

					PImage result = spectrum.getReverseTransform();
					output = filter.AutoBalance.balanceColors(result);

					System.out.println("reverse transform done");
				}
				break;

			case SCAN:
				int nbThreads2= fromClient.readInt();
				int[] scanPointsX= {fromClient.readInt(),fromClient.readInt(),fromClient.readInt(),fromClient.readInt()};
				int[] scanPointsY= {fromClient.readInt(),fromClient.readInt(),fromClient.readInt(),fromClient.readInt()};
				int formatIndex= fromClient.readInt();

				output= Scanner.scan(input, scanPointsX, scanPointsY, formatIndex, nbThreads2);

				break;

			case GA_PAINTER:	
				final Painter p= new Painter(b);		
				p.start();

				final Timer t = new Timer();
				t.scheduleAtFixedRate(new TimerTask(){ public void run(){	
					if(p.getOutput()!=null){
						try {
							Result.sendDataToStream(p.getOutput(), imageNumber, 0, toClient);
						} catch (IOException e) {
							cancel();
							p.interrupt();
						}
					}
				}}
				,0,1000l);

				return;

			default: 
				break;
			}
			Result.sendDataToStream(output.getImage(), imageNumber, 100, toClient );
			System.out.println("computationThread: image trait�e et renvoy�e");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
