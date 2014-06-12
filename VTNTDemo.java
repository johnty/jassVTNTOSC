package jass.examples.vtNavierStokes1d_noApplet;

import jass.render.*;
import jass.generators.*;
import jass.utils.*;

import java.awt.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Date;

import javax.swing.*;

import com.illposed.osc.OSCListener;
import com.illposed.osc.OSCMessage;
import com.illposed.osc.OSCPortIn;
import com.illposed.osc.OSCPortOut;

public class VTNTDemo implements Runnable {

	static final String aboutStr = "This demo uses a numerical solution of the linearized 1D Navier-Stokes PDE as well as a lip model. Select various vowels by clicking the buttons in the control panel. This will  generate a  particular vocal  tract shape  matching the  vowel and synthesize  the  sound of  exciting  such a  vocal  tract  shape with  a Rosenberg type glottal excitation. The  airway model is morphed in order to fit this  tube model.  Start the timeline in order  to see the airway change.   You can  also tweak  reflection  coefficients at  the lip  and glottis,  attenuation (damping)  and the  4 glottal  parameters  for the excitation. The formants window plots  the spectrum of the sound.  After you move  the sliders you need to  click the formants button  to see the update.\n The particular vowels implemented here are the six Russian vowels as described in \"Acoustics Theory of Speech Production\", Chapter 2.3, Gunnar Fant, 1970. Nasal tract is controlled by parameters Velum (0 nasal tract is closed, 1 vocal tract is closed) and M-N balance which determines the mix out the nose and mouth sound sources. Finally the geometry can be specified with the sliders.";

	static int nTubeSections;
	static double[] tract;// = new double[nTubeSections]; // for presets
	// static final double tubeLength=-1;
	String[] args;// = {".17","44100",".10","6","5",".5"};
	
	static RightLoadedWebsterTube filter;
	static RightLoadedWebsterTube filterCopy;
	static TubeModel tm;
	static int nAuxSliders;
	static TubeModel tmNasal;
	static int tubelengthSliderIndex;

	static SourcePlayer player;
	static Controller a_controlPanel; // VT
	static Controller a_controlPanelNasal; // nasal tract
	static Controller a_controlPanelRosenberg; // Rosenberg glottal model
	static Controller a_controlPanelTwoMass; // Ishizak-Flanagan model
	// Airway airway=null;
	static FormantsPlotter formantsPlotter;
	static boolean useTwoMassModel = true; // or if false use Rosenberg model
	static float srate;
	static boolean slideStarted = false;

	OSCPortOut oscSender;

	public String getAbout() {
		return aboutStr;
	}

	private static boolean haltMe = false;

	public void detach() {
		halt();
		System.out.println("halt!!");
	}

	public void halt() {
		System.out.println("halt!!");
		haltMe = true;
		player.stopPlaying();
		a_controlPanel.dispose();
		a_controlPanelRosenberg.dispose();
		a_controlPanelTwoMass.dispose();
		a_controlPanelNasal.dispose();
		if (formantsPlotter != null) {
			formantsPlotter.close();
		}
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public VTNTDemo(String[] args) {
		super();
		if (args.length!= 6) {
			String[] newArgs = { ".17", "44100", ".10", "6", "11", ".5" }; //AirwayXsection integration
		}
		else {
			this.args = args;
		}

		InetAddress addr;
		try {
			addr = InetAddress.getByName("127.0.0.1");
			oscSender = new OSCPortOut(addr, 9999);
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SocketException e) {
			e.printStackTrace();
		}
		OSCMessage msg = new OSCMessage("test");
		try {
			oscSender.send(msg);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		Thread thread = new Thread(this);
		thread.setPriority(Thread.MAX_PRIORITY);
		thread.start();
		/*
		 * while(airway==null) { try { Thread.sleep(50); } catch(Exception e){}
		 * } addModel(airway);
		 */
	}

	public static void restartEverything(String[] args) {
		new VTNTDemo(args);
	}
	public static void main(String[] args) {
		String[] newArgs = { ".17", "44100", ".10", "6", "11", ".5" }; //AirwayXsection integration
		new VTNTDemo(newArgs);
	}

	public void run() {
		System.out.println("####run start");
		int bufferSize = 256;// 512;
		int bufferSizeJavaSound = 1024 * 8;
		// int nchannels = 1;
		int nTubeSectionsNasal;
		/*
		 * try { airway = new Airway(ArtisynthPath.getHomeRelativePath(
		 * "src/artisynth/models/tubesounds/airway_t.obj","."),
		 * ArtisynthPath.getHomeRelativePath
		 * ("src/artisynth/models/tubesounds/fissured-tongue.jpg",".")); }
		 * catch(Exception e) { System.out.println("File not found"+e); }
		 */
		if (args.length != 6) {
			System.out
					.println("Usage: java VTNTDemo .17 srate nasalLen nNasalSections nTubeSections cflNumber");
			return;
		}
		double tubeLength = Double.parseDouble(args[0]);
		double tubeLengthNasal = Double.parseDouble(args[2]);
		nTubeSectionsNasal = Integer.parseInt(args[3]); // only for control
		nTubeSections = Integer.parseInt(args[4]); // only for control
		tract = new double[nTubeSections];
		System.out.println("# of tract segments = "+tract.length);
		srate = (float) Double.parseDouble(args[1]);
		double cflNumber = Double.parseDouble(args[5]);
		// TubeModel will decide how many segments are needed and interpolate
		tm= new TubeModel(nTubeSections); //final
		tmNasal = new TubeModel(nTubeSectionsNasal); //final
		tm.setLength(tubeLength);
		tmNasal.setLength(tubeLengthNasal);

		setupMixedData();

		player = new SourcePlayer(bufferSize, bufferSizeJavaSound, srate);
		double c = 350; // vel. of sound
		double minLen = .15;
		double minLenNasal = tubeLengthNasal;
		filter = new RightLoadedWebsterTube(srate,
				tm, minLen, tmNasal, minLenNasal, cflNumber); //final
		filter.useLipModel = !filter.useLipModel; // set to false

		filterCopy = new RightLoadedWebsterTube(
				srate, tm, minLen, tmNasal, minLenNasal, cflNumber); //final
		filterCopy.setOutputVelocity(true); // to display formants correctly
		filterCopy.useLipModel = !filterCopy.useLipModel; // set to false (will
															// be reset later
															// on)

		final FilterContainer filterContainer = new FilterContainer(srate,
				bufferSize, filter);
		final GlottalWave source = new GlottalWave(srate, bufferSize);
		final TwoMassModel twoMassSource = new TwoMassModel(bufferSize, srate);
		final RandOut randOut = new RandOut(bufferSize);
		final Silence silence = new Silence(bufferSize);

		filterCopy.setFlowNoiseLevel(0); // no noise for spectrum

		try {
			if (useTwoMassModel) {
				filter.setTwoMassModel(twoMassSource);
				filterContainer.addSource(source); // add Rosenberg source also
				// filterContainer.addSource(randOut); // add Rand source
			} else {
				filterContainer.addSource(source);
			}
			player.addSource(filterContainer);
			// player.addSource(twoMassSource);

		} catch (Exception e) {
		}

		preset("a");
		for (int i = 0; i < nTubeSections; i++) {
			tm.setRadius(i, tract[i]);
		}
		// airway.init(tm);
		filter.changeTubeModel();
		filterCopy.changeTubeModel();

		// set up control panels

		// Vocal tract control panel:
		int nbuttons = 4 + 7 + 2 + 7; // johnty: added +7 Story tubeshapes
		nAuxSliders = 5; //final
		final int nSliders = nTubeSections + nAuxSliders;
		String[] names = new String[nSliders];
		double[] val = new double[nSliders];
		double[] min = new double[nSliders];
		double[] max = new double[nSliders];
		tubelengthSliderIndex = nAuxSliders - 1; //final
		// names[0] = "f1 ";
		// val[0] = 250; min[0] = 10; max[0] = 800;
		// names[1] = "f2 ";
		// val[1] = 2000; min[1] = 801; max[1] = 10000;

		names[0] = "u_xx mult";
		val[0] = 1;
		min[0] = 0.0;
		max[0] = 20;
		names[1] = "u mult";
		val[1] = 1;
		min[1] = 0.0;
		max[1] = 20;
		names[2] = "wall coeff ";
		val[2] = 1;
		min[2] = 0;
		max[2] = 5;
		names[3] = "lipCf ";
		val[3] = 1;
		min[3] = .05;
		max[3] = 30;
		names[4] = "length ";
		val[4] = tubeLength;
		min[4] = .15;
		max[4] = tubeLength * 4;

		double minA = 0;
		double maxA = 20;
		for (int k = nAuxSliders; k < nSliders; k++) {
			names[k] = "A(" + new Integer(k - nAuxSliders).toString() + ") ";
			val[k] = 1;
			min[k] = minA;
			max[k] = maxA;
			double r = Math.sqrt(val[k] / Math.PI);
			tm.setRadius(k - nAuxSliders, r / 100); // in meters
			// tmAirway.setRadius(k-nAuxSliders,r); // in cm!
		}

		a_controlPanel = new Controller(new java.awt.Frame("Vocal Tract"),
				false, val.length, nbuttons) {
			private static final long serialVersionUID = 1L;

			boolean muted = false;

			OSCPortIn receiver;

			private void handleReset() {
				filter.reset();
				twoMassSource.reset();
				player.resetAGC();
				muted = !muted;
				player.setMute(muted);
				player.resetAGC();
				try {
					Thread.sleep(100);
				} catch (Exception e) {
				}
				;
				muted = !muted;
				player.setMute(muted);
				player.resetAGC();
			}

			public void onButton(int k) {
				switch (k) {
				case 0:
					handleReset();
					// slideStarted = !slideStarted;
					break;
				case 1: {
					FileDialog fd = new FileDialog(new Frame(), "Save");
					fd.setMode(FileDialog.SAVE);
					fd.setVisible(true);
					saveToFile(fd.getFile());
				}
					break;
				case 2: {
					FileDialog fd = new FileDialog(new Frame(), "Load");
					fd.setMode(FileDialog.LOAD);
					fd.setVisible(true);
					loadFromFile(fd.getFile());
					handleReset();
				}
					break;
				case 3: {
					muted = !muted;
					player.setMute(muted);
					player.resetAGC();
				}
					break;

				case 4: {
					preset("a");
					double tubeLen = .17;
					handlePresetChange(tubeLen);

				}
					break;
				case 5: {
					preset("o");
					double tubeLen = .185;
					handlePresetChange(tubeLen);
				}
					break;
				case 6: {
					preset("u");
					double tubeLen = .195;
					handlePresetChange(tubeLen);
				}
					break;
				case 7: {
					preset("i_");
					double tubeLen = .19;
					handlePresetChange(tubeLen);
				}
					break;
				case 8: {
					preset("i");
					double tubeLen = .165;
					handlePresetChange(tubeLen);
				}
					break;
				case 9: {
					preset("e");
					double tubeLen = .165;
					handlePresetChange(tubeLen);
				}
					break;
				case 10: {
					preset("-");
					double tubeLen = .17;
					handlePresetChange(tubeLen);
				}
					break;
				case 11: { // plot formants
					updateFormantsPlot();
				}
					break;
				case 12: { // toggle lipmodel
					filter.useLipModel = !filter.useLipModel;
					filterCopy.useLipModel = filter.useLipModel;
					if (filter.useLipModel) {
						a_controlPanel.setButtonName("ToggleLipModel (is on)",
								12);
					} else {
						a_controlPanel.setButtonName("ToggleLipModel (is off)",
								12);
					}
					handleReset();
				}
				case 13: {// johnty: added story tube shapes from here
					System.out.println("s_a");
					preset("s_a");
					handlePresetChange(storyData_a_length);
					break;
				}
				case 14: {
					System.out.println("s_o");
					preset("s_o");
					handlePresetChange(storyData_o_length);
					break;
				}
				case 15: {
					System.out.println("s_u");
					preset("s_u");
					handlePresetChange(storyData_u_length);
					break;
				}
				case 16: {
					System.out.println("s_i");
					preset("s_i");
					handlePresetChange(storyData_i_length);
					break;
				}
				case 17: {
					System.out.println("s_I");
					preset("s_I");
					handlePresetChange(storyData_I_length);
					break;
				}
				case 18: {
					System.out.println("s_p");
					preset("s_p");
					handlePresetChange(storyData_p_length);
					break;
				}
				case 19: {
					System.out.println("s_t");
					preset("s_t");
					handlePresetChange(storyData_t_length);
					break;
				}

				}
			}

			public void handlePresetChange(double tubeLen) {
				tm.setLength(tubeLen);
				val[tubelengthSliderIndex] = tubeLen;
				// min[tubelengthSliderIndex] = tubeLen;
				for (int k = nAuxSliders; k < nSliders; k++) {
					val[k] = 100 * tract[k - nAuxSliders]; // in cm!
					val[k] *= val[k];
					val[k] *= Math.PI;
				}
				a_controlPanel.setSliders(val, min, max, names);
				for (int i = 0; i < nTubeSections; i++) {
					tm.setRadius(i, tract[i]);
				}
				filter.changeTubeModel();
				handleReset();
				// updateFormantsPlot();

			}

			private void updateFormantsPlot() {
				filterCopy.changeTubeModel();
				filterCopy.reset();
				if (formantsPlotter == null) {
					formantsPlotter = new FormantsPlotter();
					formantsPlotter.setLocation(300, 500);
				}
				formantsPlotter.plotFormants(filterCopy, srate);
				// formantsPlotter.dumpData(filterCopy,srate);
			}

			final String btnName1 = "/mrmr/pushbutton/12/Johntys-iPod";
			final String btnName2 = "/mrmr/pushbutton/13/Johntys-iPod";
			final String blendSlider = "/mrmr/slider/horizontal/14/johntys-iPod";

			final int numSliders = 10;

			String[] sliderOSCNames = new String[numSliders];

			// start OSC code
			public void initOsc() {

				for (int i = 0; i < numSliders; i++) {
					sliderOSCNames[i] = "/mrmr/slider/horizontal/" + (i + 1)
							+ "/johntys-iPod";
					System.out.println(sliderOSCNames[i]);
				}

				try {

					receiver = new OSCPortIn(12000);
					OSCListener listener = new OSCListener() {

						@Override
						public void acceptMessage(Date arg0, OSCMessage msg) {
							// System.out.println("diva msg received!");

							// System.out.println(msg.getAddress());
							Object[] args = msg.getArguments();

							float oscVal = (Float) args[0];
							if (msg.getAddress().compareTo(btnName1) == 0) {
								if (oscVal == 1) {
									System.out.println("a");
									preset("a");
									filter.changeTubeModel();
									double tubeLen = .17;
									handlePresetChange(tubeLen);
								}
							} else if (msg.getAddress().compareTo(btnName2) == 0) {
								if (oscVal == 1) {
									System.out.println("o");
									preset("o");
									filter.changeTubeModel();
									double tubeLen = .185;
									handlePresetChange(tubeLen);
								}
							} else {
								System.out.print("set slider ");
								int sliderIdx = -1;
								for (int i = 0; i < numSliders; i++) {
									if (msg.getAddress().compareTo(
											sliderOSCNames[i]) == 0) {
										sliderIdx = i;
										break;
									}
								}
								if (sliderIdx != -1) {
									double sMin, sMax;
									sMin = min[sliderIdx + nAuxSliders];
									sMax = max[sliderIdx + nAuxSliders];
									val[sliderIdx + nAuxSliders] = sMin
											+ (sMax - sMin) * oscVal;
									onSlider(sliderIdx + nAuxSliders);

									System.out.println(sliderIdx + 1 + " to "
											+ val[sliderIdx + nAuxSliders]);
								}
								setSliders(val, min, max, names);

							}

						}
					};

					OSCListener testListener = new OSCListener() {

						@Override
						public void acceptMessage(Date arg0, OSCMessage arg1) {
							// TODO Auto-generated method stub
							// System.out.println("test msg received!");
						}
					};

					OSCListener vowelPlosiveBlendTester = new OSCListener() {

						@Override
						public void acceptMessage(Date arg0, OSCMessage msg) {
							Object args[] = msg.getArguments();
							// System.out.println("plosive test");

							// t and d same

							if (args.length == 1) {
								double oscVal = (Float) args[0];
								// System.out.println(oscVal);

								for (int i = 0; i < tract.length; i++) {
									double tubeSliderVal = interp_story_p[i]
											* oscVal + interp_vowels[i]
											* (1 - oscVal);

									tubeSliderVal = 100 * tubeSliderVal; // in
																			// cm!
									tubeSliderVal *= tubeSliderVal;
									tubeSliderVal *= Math.PI;

									val[i + nAuxSliders] = tubeSliderVal;
								}

								double tubeLen = storyData_p_length * oscVal
										+ (1 - oscVal) * interp_vowel_length;

								val[4] = tubeLen;

								setSliders(val, min, max, names);

							}
						}
					};
					OSCListener vowelPlosiveBlendTester2 = new OSCListener() {

						@Override
						public void acceptMessage(Date arg0, OSCMessage msg) {
							Object args[] = msg.getArguments();
							// System.out.println("plosive test2");
							// p and b

							if (args.length == 1) {
								double oscVal = (Float) args[0];
								// System.out.println(oscVal);

								for (int i = 0; i < tract.length; i++) {
									// double tubeSliderVal = interp_story_t[i]
									// * oscVal + interp_a[i]* (1-oscVal);
									double tubeSliderVal = interp_story_t[i]
											* oscVal + interp_vowels[i]
											* (1 - oscVal);

									tubeSliderVal = 100 * tubeSliderVal; // in
																			// cm!
									tubeSliderVal *= tubeSliderVal;
									tubeSliderVal *= Math.PI;

									val[i + nAuxSliders] = tubeSliderVal;
								}

								double tubeLen = storyData_t_length * oscVal
										+ (1 - oscVal) * interp_vowel_length;
								// double tubeLen = storyData_p_length* oscVal +
								// (1-oscVal) * 0.165;

								val[4] = tubeLen;

								setSliders(val, min, max, names);

							}
						}
					};

					// blend between one plosive and blended vowels
					OSCListener blendVowelBuffer = new OSCListener() {

						@Override
						public void acceptMessage(Date arg0, OSCMessage msg) {
							Object args[] = msg.getArguments();

							System.out.println("vowel buffer blend");

							double tubeWeights[] = new double[args.length];
							for (int i = 0; i < args.length; i++) {
								tubeWeights[i] = (double) (Float) args[i];
								// System.out.print(tubeWeights[i]+" ");

							}
							for (int i = 0; i < tract.length; i++) {
								double tubeSecVal = interp_a[i]
										* tubeWeights[0] + interp_o[i]
										* tubeWeights[1] + interp_u[i]
										* tubeWeights[2] + interp_i_[i]
										* tubeWeights[3] + interp_i[i]
										* tubeWeights[4] + interp_e[i]
										* tubeWeights[5] + interp__[i]
										* tubeWeights[6];

								// tubeSecVal = 100*tubeSecVal; // in cm!
								// tubeSecVal *= tubeSecVal;
								// tubeSecVal *= Math.PI;

								// **send via OSC
								// sendArgs[i] = new Float(tubeSecVal);
								// val[i+nAuxSliders] = tubeSecVal;
								//
								interp_vowels[i] = tubeSecVal;
							}

							double tubeLen = .17 * tubeWeights[0] + .185
									* tubeWeights[1] + .195 * tubeWeights[2]
									+ .19 * tubeWeights[3] + .165
									* tubeWeights[4] + .165 * tubeWeights[5]
									+ .17 * tubeWeights[6];
							// val[4] = tubeLen;
							interp_vowel_length = tubeLen;
						}
					};

					// blending between two vowels
					OSCListener vowelBlendListener = new OSCListener() {
						// used to work with ntubes = 10;
						@Override
						public void acceptMessage(Date arg0, OSCMessage msg) {
							// TODO Auto-generated method stub
							// System.out.println("vowel blend slider");

							Object args[] = msg.getArguments();

							if (args.length == 1) {
								double oscVal = (Float) args[0];
								// System.out.println(oscVal);

								for (int i = 0; i < tract.length; i++) {
									double tubeSliderVal = interp_i[i] * oscVal
											+ interp_e[i] * (1 - oscVal);

									tubeSliderVal = 100 * tubeSliderVal; // in
																			// cm!
									tubeSliderVal *= tubeSliderVal;
									tubeSliderVal *= Math.PI;

									val[i + nAuxSliders] = tubeSliderVal;
								}

								double tubeLen = 0.165 * oscVal + (1 - oscVal)
										* 0.165;

								val[4] = tubeLen;

							} else {
								// vowel interpolation via rbfs

								Object sendArgs[] = new Object[nTubeSections];

								double tubeWeights[] = new double[args.length];
								for (int i = 0; i < args.length; i++) {
									tubeWeights[i] = (double) (Float) args[i];
									// System.out.print(tubeWeights[i]+" ");

								}
								for (int i = 0; i < tract.length; i++) {
									double tubeSecVal = interp_a[i]
											* tubeWeights[0] + interp_o[i]
											* tubeWeights[1] + interp_u[i]
											* tubeWeights[2] + interp_i_[i]
											* tubeWeights[3] + interp_i[i]
											* tubeWeights[4] + interp_e[i]
											* tubeWeights[5] + interp__[i]
											* tubeWeights[6];

									tubeSecVal = 100 * tubeSecVal; // in cm!
									tubeSecVal *= tubeSecVal;
									tubeSecVal *= Math.PI;
									sendArgs[i] = new Float(tubeSecVal);
									val[i + nAuxSliders] = tubeSecVal;
								}

								double tubeLen = .17 * tubeWeights[0] + .185
										* tubeWeights[1] + .195
										* tubeWeights[2] + .19 * tubeWeights[3]
										+ .165 * tubeWeights[4] + .165
										* tubeWeights[5] + .17 * tubeWeights[6];
								val[4] = tubeLen;
								try {
									OSCMessage sendMsg = new OSCMessage(
											"/jass/tubes", sendArgs);
									oscSender.send(sendMsg);
								} catch (IOException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}

								// System.out.println("");

							}
							// update sliders
							setSliders(val, min, max, names);

						}
					};

					// from tongue model
					// this one sets individual tubes
					OSCListener tubeListener = new OSCListener() {

						@Override
						public void acceptMessage(Date arg0, OSCMessage msg) {
							// TODO Auto-generated method stub
							
							// this one sets tube sections individually
							Object args[] = msg.getArguments();
							System.out.println("tube params received: size="+args.length);
							//System.out.println("tract size="+tract.length);
							if (args.length == tract.length) {
								
								for (int i = 0; i < tract.length; i++) {
									double tubeSliderVal = (Float) args[i] / 100.0; //try this scale for now 
									System.out.print(tubeSliderVal+ " ");
									//tubeSliderVal = 100*tubeSliderVal; // in cm!
									
									//uncomment next two lines if we are receiving radius instead of area;
									//tubeSliderVal *= tubeSliderVal;
									//tubeSliderVal *= Math.PI;

									val[i + nAuxSliders] = tubeSliderVal;
								}
								System.out.println();
								setSliders(val, min, max, names);
							}
							else  if (args.length == 4) {
								//System.out.println("kin tube interp!");
								double blend_i = (Float) args[0];
								double blend_e = (Float) args[1];
								double blend_a = (Float) args[2];
								double blend_u = (Float) args[3];
								for (int i = 0; i < tract.length; i++) {
									double tubeSliderVal = blend_i*force_i[i] + 
															blend_e*force_e[i] + 
															blend_a*force_a[i] + 
															blend_u*force_u[i];

									//tubeSliderVal = 100*tubeSliderVal; // in cm!
									tubeSliderVal *= tubeSliderVal;
									tubeSliderVal *= Math.PI;

									val[i + nAuxSliders] = tubeSliderVal;
								}
								setSliders(val, min, max, names);
								
							}
							else if (args.length == 2) {
								//set # of tube sections
								String tag = (String) args[0];
								if (tag.equals("/numTubeSections")) {
									int numTubes = (Integer) args[1];
									System.out.println("reset numTubeSections to "+numTubes);
									String numTubesStr = Integer.toString(numTubes);
									String[] newArgs = { ".17", "44100", ".10", "6", numTubesStr, ".5" };
									halt();
									restartEverything(newArgs);
								}
							}
							else {
								//output mode
								System.out.println("*****spitting out tube values*****");
								System.out.println(" ");
								for (int i = 0; i < tract.length; i++) {
									
									System.out.print(Math.sqrt( val[i + nAuxSliders]/Math.PI )+" ");
								}
								System.out.println("\n\n");
								System.out.println("*****done out tube values*****");
							}

						}
					};

					OSCListener ctrlListener = new OSCListener() {

						@Override
						public void acceptMessage(Date arg0, OSCMessage arg1) {
							// TODO Auto-generated method stub
							System.out.println("reset from OSC");
							handleReset();

						}
					};

					receiver.addListener(btnName1, listener);
					receiver.addListener(btnName2, listener);
					for (int i = 0; i < numSliders; i++) {
						receiver.addListener(sliderOSCNames[i], listener);
					}
					receiver.addListener(blendSlider, vowelBlendListener);
					receiver.addListener("/artisynth/test/ptest",
							vowelPlosiveBlendTester);
					receiver.addListener("/artisynth/test/ptest2",
							vowelPlosiveBlendTester2);

					receiver.addListener("/artisynth/vowels",
							vowelBlendListener);

					receiver.addListener("/artisynth/test/vowelbuffer",
							blendVowelBuffer);

					receiver.addListener("/tubes", tubeListener);
					receiver.addListener("/test", testListener);

					receiver.addListener("/artisynth/ctrl/reset", ctrlListener);

					receiver.startListening();

				} catch (Exception e) {
				}

				// end OSC code

			}

			public void onSlider(int k) {
				switch (k) {
				/*
				 * case 0: filter.setOm1(2*Math.PI*this.val[k]);
				 * filterCopy.setOm1(2*Math.PI*this.val[k]);
				 * filter.changeTubeModel(); break; case 1:
				 * filter.setOm2(2*Math.PI*this.val[k]);
				 * filterCopy.setOm2(2*Math.PI*this.val[k]);
				 * filter.changeTubeModel(); break;
				 */
				case 0:
					filter.multDSecond = this.val[k];
					filterCopy.multDSecond = this.val[k];
					filter.changeTubeModel();
					break;
				case 1:
					filter.multDWall = this.val[k];
					filterCopy.multDWall = this.val[k];
					break;
				case 2:
					filter.setWallPressureCoupling((double) this.val[k]);
					filterCopy.setWallPressureCoupling((double) this.val[k]);
					filter.changeTubeModel();
					break;
				case 3:
					filter.lipAreaMultiplier = (double) this.val[k];
					filterCopy.lipAreaMultiplier = (double) this.val[k];
					filter.changeTubeModel();
					break;
				case 4:
					tm.setLength((double) this.val[k]);
					filter.changeTubeModel();
					break;
				default:
					// System.out.println("slide"+ val[k]);
					double r = Math.sqrt(val[k] / Math.PI);
					tm.setRadius(k - nAuxSliders, r / 100);// in meters
					// tmAirway.setRadius(k-nAuxSliders,r);// in cm
					filter.changeTubeModel();
					break;
				}
			}
		};

		a_controlPanel.addWindowListener(new java.awt.event.WindowAdapter() {
			public void windowClosing(java.awt.event.WindowEvent e) {
				System.out.println("Close handler called");
				player.stopPlaying();
			}
		});

		a_controlPanel.setSliders(val, min, max, names);
		a_controlPanel.setButtonNames(new String[] { "Reset", "Save", "Load",
				"(Un)mute", "[a]", "[o]", "[u]", "[i-]", "[i]", "[e]", "[-]",
				"Formants", "ToggleLipModel (is on)", "[s_a]", "[s_o]",
				"[s_u]", "[s_i]", "[s_I]", "[s_p]", "[s_t]" });
		a_controlPanel.setVisible(true);
		a_controlPanel.onButton(nbuttons - 1); // put up formants
		a_controlPanel.initOsc();

		// End Vocal tract control panel:

		// Nasal tract control panel:
		int nbuttonsNasal = 2;
		final int nAuxSlidersNasal = 3;
		int nSlidersNasal = nTubeSectionsNasal + nAuxSlidersNasal;
		String[] namesNasal = new String[nSlidersNasal];
		final double[] valNasal = new double[nSlidersNasal];
		double[] minNasal = new double[nSlidersNasal];
		double[] maxNasal = new double[nSlidersNasal];
		namesNasal[0] = "Velum(0noNasal)";
		valNasal[0] = 0.0;
		minNasal[0] = 0;
		maxNasal[0] = 1;
		namesNasal[1] = "M-N Bal";
		valNasal[1] = .5;
		minNasal[1] = 0;
		maxNasal[1] = 1;
		namesNasal[2] = "NasalLen";
		valNasal[2] = .11;
		minNasal[2] = .1;
		maxNasal[2] = .18;
		double minANasal = .01;
		double maxANasal = 10;
		double[] dangHondaFig6 = { .7, 1.5, 5, 1, .8, .5, .6, .8 }; // 8 sliders
		int ii = 0;
		for (int k = nAuxSlidersNasal; k < nSlidersNasal; k++, ii++) {
			namesNasal[k] = "A(" + new Integer(k - nAuxSlidersNasal).toString()
					+ ") ";
			valNasal[k] = dangHondaFig6[ii];
			minNasal[k] = minANasal;
			maxNasal[k] = maxANasal;
			double r = Math.sqrt(valNasal[k] / Math.PI);
			tmNasal.setRadius(k - nAuxSlidersNasal, r / 100); // in meters
		}

		a_controlPanelNasal = new Controller(new java.awt.Frame("Nasal Tract"),
				false, valNasal.length, nbuttonsNasal) {
			private static final long serialVersionUID = 2L;

			boolean muted = false;

			public void onButton(int k) {
				switch (k) {
				case 0: {
					FileDialog fd = new FileDialog(new Frame(), "Save");
					fd.setMode(FileDialog.SAVE);
					fd.setVisible(true);
					saveToFile(fd.getFile());
				}
					break;
				case 1: {
					FileDialog fd = new FileDialog(new Frame(), "Load");
					fd.setMode(FileDialog.LOAD);
					fd.setVisible(true);
					loadFromFile(fd.getFile());
				}
					break;

				}
			}

			public void onSlider(int k) {
				switch (k) {
				case 0:
					filter.velumNasal = this.val[k];
					filterCopy.velumNasal = this.val[k];
					break;
				case 1:
					filter.mouthNoseBalance = this.val[k];
					filterCopy.mouthNoseBalance = this.val[k];
					break;
				case 2:
					tmNasal.setLength((double) this.val[k]);
					filter.changeTubeModel();
					break;
				default:
					double r = Math.sqrt(this.val[k] / Math.PI);
					tmNasal.setRadius(k - nAuxSlidersNasal, r / 100);// in
																		// meters
					filter.changeTubeModel();
					break;
				}
			}

		};

		a_controlPanelNasal
				.setSliders(valNasal, minNasal, maxNasal, namesNasal);
		a_controlPanelNasal.setButtonNames(new String[] { "Save", "Load" });
		a_controlPanelNasal.setVisible(true);

		// End Nasal tract control panel:

		// Rosenberg glottal source control panel:
		int nbuttonsRosenberg = 2;
		int nSlidersRosenberg = 4;
		String[] namesRosenberg = new String[nSlidersRosenberg];
		double[] valRosenberg = new double[nSlidersRosenberg];
		double[] minRosenberg = new double[nSlidersRosenberg];
		double[] maxRosenberg = new double[nSlidersRosenberg];

		namesRosenberg[0] = "freq";
		valRosenberg[0] = 100;
		minRosenberg[0] = 20;
		maxRosenberg[0] = 1000;
		namesRosenberg[1] = "openQ";
		valRosenberg[1] = .5;
		minRosenberg[1] = 0.001;
		maxRosenberg[1] = 1;
		namesRosenberg[2] = "slopeQ";
		valRosenberg[2] = 4;
		minRosenberg[2] = .15;
		maxRosenberg[2] = 10;
		namesRosenberg[3] = "gain";
		valRosenberg[3] = 0.0;
		minRosenberg[3] = 0;
		maxRosenberg[3] = 1;

		a_controlPanelRosenberg = new Controller(new java.awt.Frame(
				"Rosenberg Glottal Model"), false, valRosenberg.length,
				nbuttonsRosenberg) {
			private static final long serialVersionUID = 1L;

			public void onButton(int k) {
				switch (k) {
				case 0: {
					FileDialog fd = new FileDialog(new Frame(), "Save");
					fd.setMode(FileDialog.SAVE);
					fd.setVisible(true);
					saveToFile(fd.getFile());
				}
					break;
				case 1: {
					FileDialog fd = new FileDialog(new Frame(), "Load");
					fd.setMode(FileDialog.LOAD);
					fd.setVisible(true);
					loadFromFile(fd.getFile());
				}
					break;
				}
			}

			public void onSlider(int k) {
				switch (k) {
				case 0:
					source.setFrequency((float) this.val[k]);
					break;
				case 1:
					source.setOpenQuotient((float) this.val[k]);
					break;
				case 2:
					source.setSpeedQuotient((float) this.val[k]);
					break;
				case 3:
					source.setVolume((float) this.val[k]);
					break;
				default:
					break;
				}
			}
		};

		a_controlPanelRosenberg.setSliders(valRosenberg, minRosenberg,
				maxRosenberg, namesRosenberg);
		a_controlPanelRosenberg.setButtonNames(new String[] { "Save", "Load" });
		a_controlPanelRosenberg.setVisible(true);
		// end Rosenberg panel

		// Ishizak-Flanagan twomass model panel
		int nbuttonsTwoMass = 2;
		int nSlidersTwoMass = 6;
		String[] namesTwoMass = new String[nSlidersTwoMass];
		double[] valTwoMass = new double[nSlidersTwoMass];
		double[] minTwoMass = new double[nSlidersTwoMass];
		double[] maxTwoMass = new double[nSlidersTwoMass];

		namesTwoMass[0] = "q(freq)";
		valTwoMass[0] = 1;
		minTwoMass[0] = .05;
		maxTwoMass[0] = 6;
		namesTwoMass[1] = "p-lung";
		valTwoMass[1] = 500;
		minTwoMass[1] = 0;
		maxTwoMass[1] = 6000;
		namesTwoMass[2] = "Ag0(cm^2)";
		valTwoMass[2] = -.005;
		minTwoMass[2] = -.5;
		maxTwoMass[2] = .5;
		namesTwoMass[3] = "noiseLevel";
		valTwoMass[3] = .3;
		minTwoMass[3] = 0;
		maxTwoMass[3] = 10;
		namesTwoMass[4] = "noiseFreq.";
		valTwoMass[4] = 1500;
		minTwoMass[4] = 200;
		maxTwoMass[4] = 10000;
		namesTwoMass[5] = "noiseBW";
		valTwoMass[5] = 6000;
		minTwoMass[5] = 250;
		maxTwoMass[5] = 10000;

		a_controlPanelTwoMass = new Controller(new java.awt.Frame(
				"TwoMass Glottal Model"), false, valTwoMass.length,
				nbuttonsTwoMass) {
			private static final long serialVersionUID = 1L;

			OSCPortIn receiverTwoMass;

			public void onButton(int k) {
				switch (k) {
				case 0: {
					FileDialog fd = new FileDialog(new Frame(), "Save");
					fd.setMode(FileDialog.SAVE);
					fd.setVisible(true);
					saveToFile(fd.getFile());
				}
					break;
				case 1: {
					FileDialog fd = new FileDialog(new Frame(), "Load");
					fd.setMode(FileDialog.LOAD);
					fd.setVisible(true);
					loadFromFile(fd.getFile());
				}
					break;
				}
			}

			public void onSlider(int k) {
				switch (k) {
				case 0:
					// q factor of two mass model
					twoMassSource.getVars().q = this.val[k];
					// twoMassSource.getVars().setVars();
					break;
				case 1:
					// lung pressure
					twoMassSource.getVars().ps = this.val[k];
					// twoMassSource.getVars().setVars();
					break;
				case 2:
					// glottal rest area (displayed in cm^2)
					twoMassSource.getVars().Ag0 = 1.e-4 * this.val[k];
					// twoMassSource.getVars().setVars();
					break;
				case 3:
					twoMassSource.setFlowNoiseLevel(this.val[k]);
					break;
				case 4:
					twoMassSource.setFlowNoiseFrequency(this.val[k]);
					break;
				case 5:
					twoMassSource.setFlowNoiseBandwidth(this.val[k]);
					break;
				default:
					break;
				}
			}

			public void initOsc() {

				try {
					receiverTwoMass = new OSCPortIn(12002);

					// this one sets two mass model params
					OSCListener twoMassListener = new OSCListener() {

						@Override
						public void acceptMessage(Date arg0, OSCMessage msg) {
							// TODO Auto-generated method stub
							// System.out.print("tm osc received: ");
							Object args[] = msg.getArguments();
							float isOn = (Float) args[0];
							// System.out.println(isOn);

							// if (isOn == 1.0) val[1] = 800.0;
							// else val[1] = 0.0;
							val[1] = isOn;
							a_controlPanelTwoMass.setSliders(val, min, max,
									names);

						}
					};

					OSCListener freqListener = new OSCListener() {

						@Override
						public void acceptMessage(Date arg0, OSCMessage msg) {
							// TODO Auto-generated method stub
							Object args[] = msg.getArguments();
							float freq = (Float) args[0];
							val[0] = freq;
							a_controlPanelTwoMass.setSliders(val, min, max,
									names);
						}
					};

					OSCListener tmParamsListener = new OSCListener() {

						@Override
						public void acceptMessage(Date arg0, OSCMessage msg) {
							// TODO Auto-generated method stub
							Object args[] = msg.getArguments();
							float ag0 = (Float) args[0];
							float nLvl = (Float) args[1];
							// todo: add noise freq and bw?
							val[2] = ag0;
							val[3] = nLvl;
							a_controlPanelTwoMass.setSliders(val, min, max,
									names);

						}
					};
					receiverTwoMass.addListener("/artisynth/twomass/lung",
							twoMassListener);
					// receiver.addListener("/artisynth/twomass/lung_val",
					// twoMassListener);
					receiverTwoMass.addListener("/artisynth/twomass/freq",
							freqListener);
					receiverTwoMass.addListener("/artisynth/twomass/misc",
							tmParamsListener);
					receiverTwoMass.startListening();
				} catch (Exception e) {

				}
			}

		};

		a_controlPanelTwoMass.setSliders(valTwoMass, minTwoMass, maxTwoMass,
				namesTwoMass);
		a_controlPanelTwoMass.setButtonNames(new String[] { "Save", "Load" });
		a_controlPanelTwoMass.setVisible(true);
		a_controlPanelTwoMass.initOsc();
		// end Ishizak-Flanagan twomass model panel

		// add airflow monitor
		final JProgressBar progressBar = new JProgressBar(0, 1000);
		progressBar.setValue(0);
		progressBar.setStringPainted(true);
		a_controlPanelTwoMass.add(progressBar);
		a_controlPanelTwoMass.getContentPane().setLayout(
				new java.awt.GridLayout(nSlidersTwoMass + (nbuttonsTwoMass + 2)
						/ 2, 2));
		a_controlPanelTwoMass.pack();

		// set locations of panels on screen
		a_controlPanel.setLocation(new Point(740, 10));
		Point p = a_controlPanel.getLocation();
		p.translate(430, 0);
		a_controlPanelNasal.setLocation(p);
		p.translate(0, 300);
		a_controlPanelRosenberg.setLocation(p);
		p.translate(0, 150);
		a_controlPanelTwoMass.setLocation(p);

		player.start();

		try {
			Thread.sleep(1000);
		} catch (Exception e) {
		}
		;
		filter.reset();
		try {
			Thread.sleep(100);
		} catch (Exception e) {
		}
		;
		player.resetAGC();

		int sleepms = 100 / 100;
		double maxug = .00000001;
		double L0 = .17;
		double LL = L0;
		double L1 = .20;
		double dL = .0002;
		boolean goUp = true;
		while (!haltMe) {
			try {
				Thread.sleep(sleepms);
				if (slideStarted) {
					tm.setLength(LL);
					if (goUp) {
						LL += dL;
					} else {
						LL -= dL;
					}
					if (LL < L0) {
						goUp = true;
					}
					if (LL > L1) {
						goUp = false;
					}
					filter.changeTubeModel();
				}
				/*
				 * double ug = twoMassSource.getUg(); ug = Math.abs(ug);
				 * if(ug>maxug) { maxug=ug; } int pval = (int)(1000*ug/maxug);
				 * progressBar.setValue(pval);
				 * progressBar.setString("ug="+String.valueOf(ug));
				 */
			} catch (Exception e) {
			}
			// airway.scale(tm);
		}
		System.out.println("####run end");

	}

	double[] fantData_a = new double[] { 5, 5, 5, 5, 6.5, 8, 8, 8, 8, 8, 8, 8,
			8, 6.5, 5, 4, 3.2, 1.6, 2.6, 2.6, 2, 1.6, 1.3, 1, .65, .65, .65, 1,
			1.6, 2.6, 4, 1, 1.3, 1.6, 2.6 };
	double[] fantData_o = new double[] { 3.2, 3.2, 3.2, 3.2, 6.5, 13, 13, 16,
			13, 10.5, 10.5, 8, 8, 6.5, 6.5, 5, 5, 4, 3.2, 2, 1.6, 2.6, 1.3,
			.65, .65, 1, 1, 1.3, 1.6, 2, 3.2, 4, 5, 5, 1.3, 1.3, 1.6, 2.6 };
	double[] fantData_u = new double[] { .65, .65, .32, .32, 2, 5, 10.5, 13,
			13, 13, 13, 10.5, 8, 6.5, 5, 3.2, 2.6, 2, 2, 2, 1.6, 1.3, 2, 1.6,
			1, 1, 1, 1.3, 1.6, 3.2, 5, 8, 8, 10.5, 10.5, 10.5, 2, 2, 2.6, 2.6 };
	double[] fantData_i_ = new double[] { 6.5, 6.5, 2, 6.5, 8, 8, 8, 5, 3.2,
			2.6, 2, 2, 1.6, 1.3, 1, 1, 1.3, 1.6, 2.6, 2, 4, 5, 6.5, 6.5, 8,
			10.5, 10.5, 10.5, 10.5, 10.5, 13, 13, 10.5, 10.5, 6, 3.2, 3.2, 3.2,
			3.2 };
	double[] fantData_i = new double[] { 4, 4, 3.2, 1.6, 1.3, 1, .65, .65, .65,
			.65, .65, .65, .65, 1.3, 2.6, 4, 6.5, 8, 8, 10.5, 10.5, 10.5, 10.5,
			10.5, 10.5, 10.5, 10.5, 10.5, 8, 8, 2, 2, 2.6, 3.2 };
	double[] fantData_e = new double[] { 8, 8, 5, 5, 4, 2.6, 2, 2.6, 2.6, 3.2,
			4, 4, 4, 5, 5, 6.5, 8, 6.5, 8, 10.5, 10.5, 10.5, 10.5, 10.5, 8, 8,
			6.5, 6.5, 6.5, 6.5, 1.3, 1.6, 2, 2.6 };

	double[] fantData__ = new double[] { 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
			5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5 };

	double[] storyData_l = new double[] { 0.55, 0.648461538, 1.073076923,
			2.344615385, 3.336923077, 3.475384615, 3.237692308, 3.159230769,
			2.750769231, 3.223846154, 3.663076923, 3.600769231, 2.76, 2.4,
			2.336923077, 2.337692308, 2.194615385, 2.276153846, 2.264615385,
			2.324615385, 2.430769231, 2.463076923, 2.578461538, 2.656153846,
			3.009230769, 3.6, 4.3, 5.246153846, 6.017692308, 6.486153846,
			6.767692308, 6.763846154, 5.695384615, 3.976153846, 2.203846154,
			0.623076923, 2.526153846, 4.660769231, 4.634615385, 3.7 };

	double storyData_l_length = 0.1825;

	double[] storyData_a = new double[] { 0.45, 0.206153846, 0.24974359,
			0.243846154, 0.311794872, 0.315384615, 0.773076923, 1.10025641,
			0.898461538, 0.646923077, 0.386666667, 0.262564103, 0.268461538,
			0.26, 0.306923077, 0.284615385, 0.356923077, 0.593333333,
			1.116923077, 1.057692308, 1.644102564, 2.162307692, 2.616410256,
			2.808717949, 2.933846154, 3.431794872, 4.316666667, 4.976923077,
			5.900769231, 6.536410256, 6.288461538, 6.210769231, 5.753846154,
			5.056923077, 4.295641026, 4.023333333, 4.213076923, 4.265897436,
			4.646923077, 5.03 };
	double storyData_a_length = 0.1746;

	double[] storyData_o = new double[] { 0.18, 0.176153846, 0.24025641,
			0.375384615, 0.946923077, 1.531794872, 1.298461538, 0.901794872,
			0.975897436, 2.588461538, 2.690769231, 1.954871795, 1.869230769,
			1.686666667, 1.598974359, 1.390769231, 1.382564103, 1.321025641,
			0.95, 1.231538462, 1.365128205, 1.031538462, 0.645897436,
			0.434871795, 0.357692308, 0.461025641, 0.896666667, 1.306153846,
			2.173333333, 2.972051282, 3.79, 4.567692308, 5.907435897,
			7.153846154, 5.816153846, 3.496666667, 1.870769231, 0.844102564,
			0.41974359, 0.14, };
	double storyData_o_length = 0.1746;

	double[] storyData_u = new double[] { 0.4, 0.364615385, 0.326153846,
			0.485384615, 1.27, 2.635384615, 2.882307692, 2.349230769,
			2.453076923, 4.487692308, 5.736153846, 5.491538462, 4.896923077,
			4.56, 4.188461538, 3.55, 3.273076923, 3.252307692, 3.240769231,
			2.398461538, 2.07, 1.943076923, 1.22, 0.465384615, 0.174615385,
			0.209230769, 0.22, 0.405384615, 0.649230769, 0.806153846,
			1.450769231, 2.227692308, 2.534615385, 3.863846154, 5.236923077,
			3.663846154, 1.695384615, 0.871538462, 0.465384615, 0.86 };
	double storyData_u_length = 0.1746;

	double[] storyData_i = new double[] { 0.33, 0.303076923, 0.357948718,
			0.392307692, 0.643076923, 0.994871795, 2.651538462, 2.974102564,
			2.59025641, 2.905384615, 3.60025641, 3.788717949, 4.130769231,
			4.45, 4.44974359, 4.622307692, 4.548717949, 4.197435897,
			4.094615385, 3.524871795, 2.926410256, 2.001538462, 1.624102564,
			1.320769231, 0.946153846, 0.529487179, 0.34, 0.243076923,
			0.111282051, 0.129230769, 0.208461538, 0.244102564, 0.32974359,
			0.310769231, 0.339487179, 0.590512821, 1.436923077, 2.001794872,
			2.012051282, 1.58 };
	double storyData_i_length = 0.1667;

	double[] storyData_I = new double[] { 0.2, 0.170512821, 0.18, 0.167692308,
			0.301025641, 1.228717949, 1.653846154, 1.478461538, 1.079230769,
			1.016923077, 1.822307692, 2.637948718, 2.889230769, 3.276666667,
			3.351025641, 3.443076923, 3.873846154, 3.811794872, 3.873076923,
			3.480512821, 2.970769231, 2.600769231, 2.321282051, 1.973846154,
			1.853846154, 1.626666667, 1.443333333, 1.3, 0.977179487,
			0.811538462, 0.916923077, 1.195641026, 1.604102564, 1.814615385,
			1.733333333, 1.914615385, 1.927692308, 1.650769231, 1.373333333,
			1.18 };
	double storyData_I_length = 0.1667;

	double[] storyData_p = new double[] { 0.31, 0.39, .42, .71, 1.28, 1.80,
			1.70, 1.43, 1.25, 0.9, 2.06, 2.77, 2.19, 2.35, 2.67, 2.17, 1.77,
			2.09, 2.16, 2.26, 2.26, 2.29, 2.17, 2.13, 2.64, 2.65, 2.30, 2.12,
			1.67, 1.44, 1.16, 1.51, 1.76, 1.93, 1.98, 2.21, 2.35, 2.45, 2.37,
			2.47, 1.75, 1.09, 0.70, 0.1 };

	double storyData_p_length = 0.1746;

	double[] storyData_t = new double[] { 0.38, 0.50, 0.40, 1.07, 1.38, 1.65,
			1.29, 1.01, 0.92, 0.86, 1.03, 1.60, 2.46, 2.24, 2.47, 2.84, 2.74,
			3.32, 3.83, 3.97, 4.16, 4.41, 4.11, 3.95, 3.64, 3.37, 2.89, 2.61,
			2.69, 2.32, 2.04, 1.64, 1.39, 1.26, 0.87, 0.60, 0.10, 0.05, 0.05,
			0.13, 0.18, 1.48, 1.60, 1.43 };

	double storyData_t_length = 0.1746;

	/*FORCE/KIN stuff */
	double[] force_i = new double[]{1.0, 0.800000011920929, 0.6000000238418579, 1.0, 1.4973688125610352, 1.1740292310714722, 1.0345096588134766, 0.9506478905677794, 0.8654797077178955, 0.7958773374557495, 0.7806113958358765, 0.6716582179069519, 0.5784298181533813, 0.543100893497467, 0.46159416437149053, 0.4854542315006256, 0.5330904722213745, 0.6914271712303162, 0.3793049454689026, 0.3499999940395355, 0.612500011920929, 0.875 };
	
	double[] force_e = new double[] {1.0, 0.800000011920929, 0.6000000238418579, 1.0, 1.5, 1.197868824005127, 1.0617122650146484, 0.9727923274040222, 0.8889038562774658, 0.8188197016716003, 0.8264136910438539, 0.7442684769630432, 0.5612082481384277, 0.5763142704963684, 0.5392224788665771, 0.5719009041786194, 0.7530128359794617, 0.9341247081756592, 0.7117263078689575, 0.25, 0.612500011920929, 0.875 };
	
	double[] force_a = new double[] { 1.0, 0.800000011920929, 0.6000000238418579, 1.0, 1.2770581245422363, 0.9243246912956238, 0.6660170555114746, 0.5084545016288757, 0.5145794153213501, 0.5408669710159302, 0.5411776900291443, 0.7120235562324524, 0.7890691757202148, 0.8275006413459778, 0.9204881787300109, 1.013475775718689, 1.4276714324951172, 1.5, 1.5, 0.3499999940395355, 0.612500011920929, 0.875};
	
	double[] force_u = new double[] {
			1.0, 0.800000011920929, 0.6000000238418579, 1.0, 1.4749692678451538, 1.0945264101028442, 0.873370885848999, 0.6739974617958069, 0.5485923290252686, 0.5354825258255005, 0.42789447307586664, 0.44623115658760076, 0.48870614171028137, 0.4434795379638672, 0.5512247085571289, 0.6589698791503906, 1.1057937145233154, 1.5, 1.5, 0.3499999940395355, 0.612500011920929, 0.875
	};

	double[] mixedData = new double[35];

	double[] interp_a;
	double[] interp_o;
	double[] interp_u;
	double[] interp_i_;
	double[] interp_i;
	double[] interp_e;
	double[] interp__;

	double[] interp_vowels;
	double interp_vowel_length = storyData_a_length;

	double[] interp_story_t;
	double[] interp_story_p;

	public void setupMixedData() {

		interp_a = interpolateTractParams(fantData_a);
		interp_o = interpolateTractParams(fantData_o);
		interp_u = interpolateTractParams(fantData_u);
		interp_i_ = interpolateTractParams(fantData_i_);
		interp_i = interpolateTractParams(fantData_i);
		interp_e = interpolateTractParams(fantData_e);
		interp__ = interpolateTractParams(fantData__);
		storyData_l = flipTract(storyData_l);
		storyData_a = flipTract(storyData_a);
		storyData_o = flipTract(storyData_o);
		storyData_u = flipTract(storyData_u);
		storyData_i = flipTract(storyData_i);
		storyData_I = flipTract(storyData_I);
		storyData_p = flipTract(storyData_p);
		storyData_t = flipTract(storyData_t);

		interp_story_t = interpolateTractParams(storyData_t);
		interp_story_p = interpolateTractParams(storyData_p);

		// just set to any vowel to start
		interp_vowels = interpolateTractParams(fantData_a);

	}

	public void preset(String p) {
		double[] f_a = null;
		if (p == "a") {
			f_a = fantData_a;
		}
		if (p == "o") {
			f_a = fantData_o;
		}
		if (p == "u") {
			f_a = fantData_u;
		}
		if (p == "i_") {
			f_a = fantData_i_;
		}
		if (p == "i") {
			f_a = fantData_i;
		}
		if (p == "e") {
			f_a = fantData_e;
		}
		if (p == "-") {
			f_a = fantData__;
		}
		if (p == "x") {
			// this is for the weighted mixture of vowel sounds, determined
			// by received OSC message from DIVA
			f_a = mixedData;

		}
		if (p == "s_l") {
			f_a = storyData_l;
		}
		if (p == "s_a") {
			f_a = storyData_a;
		}
		if (p == "s_o") {
			f_a = storyData_o;
		}
		if (p == "s_u") {
			f_a = storyData_u;
		}
		if (p == "s_i") {
			f_a = storyData_i;
		}
		if (p == "s_I") {
			f_a = storyData_I;
		}
		if (p == "s_p") {
			f_a = storyData_p;
		}
		if (p == "s_t") {
			f_a = storyData_t;
		}
		// interpolate and invert Fant data
		double C = (f_a.length - 1.) / (tract.length - 1);
		for (int i = 0; i < tract.length; i++) {
			double k = i * C;
			int ki = (int) k;
			double kfrac = k - ki;
			int i1 = ki;
			int i2 = i1 + 1;
			if (i2 > f_a.length - 1) {
				i2 = i1;
			}
			// radii in meters
			tract[tract.length - i - 1] = Math
					.sqrt((f_a[i1] * (1 - kfrac) + f_a[i2] * kfrac) / Math.PI) / 100;
		}
	}

	private double[] flipTract(double[] input) {
		double[] output = new double[input.length];
		for (int i = 0; i < input.length; i++) {
			output[input.length - 1 - i] = input[i];
		}

		return output;
	}

	// helper function to mix different combination of tube shapes
	private double[] interpolateTractParams(double[] fantValues) {
		double C = (fantValues.length - 1.) / (tract.length - 1);
		double[] interpValues = new double[tract.length];
		for (int i = 0; i < tract.length; i++) {
			double k = i * C;
			int ki = (int) k;
			double kfrac = k - ki;
			int i1 = ki;
			int i2 = i1 + 1;
			if (i2 > fantValues.length - 1) {
				i2 = i1;
			}
			//
			interpValues[tract.length - i - 1] = Math.sqrt((fantValues[i1]
					* (1 - kfrac) + fantValues[i2] * kfrac)
					/ Math.PI) / 100;

		}
		return interpValues;
	}
}
