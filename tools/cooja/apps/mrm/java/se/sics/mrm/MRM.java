/*
 * Copyright (c) 2006, Swedish Institute of Computer Science.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 * $Id: MRM.java,v 1.12 2010/12/02 15:25:50 fros4943 Exp $
 */

package se.sics.mrm;

import java.util.Collection;
import java.util.Hashtable;
import java.util.Observable;
import java.util.Observer;
import java.util.Random;

import org.apache.log4j.Logger;
import org.jdom.Element;

import se.sics.cooja.ClassDescription;
import se.sics.cooja.RadioConnection;
import se.sics.cooja.Simulation;
import se.sics.cooja.interfaces.DirectionalAntennaRadio;
import se.sics.cooja.interfaces.NoiseSourceRadio;
import se.sics.cooja.interfaces.NoiseSourceRadio.NoiseLevelListener;
import se.sics.cooja.interfaces.Position;
import se.sics.cooja.interfaces.Radio;
import se.sics.cooja.plugins.Visualizer;
import se.sics.cooja.radiomediums.AbstractRadioMedium;
import se.sics.mrm.ChannelModel.Parameter;
import se.sics.mrm.ChannelModel.RadioPair;
import se.sics.mrm.ChannelModel.TxPair;

/**
 * Multi-path Ray-tracing radio medium (MRM).
 *
 * MRM is an alternative to the simpler radio mediums available in
 * COOJA. It is packet based and uses a 2D ray-tracing approach to approximate
 * signal strength attenuation between simulated radios. Currently the
 * ray-tracing only supports reflections and refractions through homogeneous
 * obstacles.
 *
 * MRM provides two plugins: one for visualizing the radio environment,
 * and one for configuring the radio medium parameters.
 *
 * Future work includes adding support for diffraction and scattering.
 *
 * MRM supports both noise source radios and directional antenna radios.
 * 
 * @see DirectionalAntennaRadio
 * @see NoiseSourceRadio
 * @author Fredrik Osterlind
 */
@ClassDescription("Multi-path Ray-tracer Medium (MRM)")
public class MRM extends AbstractRadioMedium {
  private static Logger logger = Logger.getLogger(MRM.class);

  public final static boolean WITH_NOISE = true; /* NoiseSourceRadio */
  public final static boolean WITH_DIRECTIONAL = true; /* DirectionalAntennaRadio */

  private Observer channelModelObserver = null;

  private boolean WITH_CAPTURE_EFFECT;
  private double CAPTURE_EFFECT_THRESHOLD;
  private double CAPTURE_EFFECT_PREAMBLE_DURATION;
  
  private Simulation sim;
  private Random random = null;
  private ChannelModel currentChannelModel = null;

  /**
   * Notifies observers when this radio medium has changed settings.
   */
  private SettingsObservable settingsObservable = new SettingsObservable();

  /**
   * Creates a new Multi-path Ray-tracing Medium (MRM).
   */
  public MRM(Simulation simulation) {
    super(simulation);

    sim = simulation;
    random = simulation.getRandomGenerator();
    currentChannelModel = new ChannelModel(sim);
    
    WITH_CAPTURE_EFFECT = currentChannelModel.getParameterBooleanValue(ChannelModel.Parameter.captureEffect);
    CAPTURE_EFFECT_THRESHOLD = currentChannelModel.getParameterDoubleValue(ChannelModel.Parameter.captureEffectSignalTreshold);
    CAPTURE_EFFECT_PREAMBLE_DURATION = currentChannelModel.getParameterDoubleValue(ChannelModel.Parameter.captureEffectPreambleDuration);
   
    currentChannelModel.addSettingsObserver(channelModelObserver = new Observer() {
      public void update(Observable o, Object arg) {
        WITH_CAPTURE_EFFECT = currentChannelModel.getParameterBooleanValue(ChannelModel.Parameter.captureEffect);
        CAPTURE_EFFECT_THRESHOLD = currentChannelModel.getParameterDoubleValue(ChannelModel.Parameter.captureEffectSignalTreshold);
        CAPTURE_EFFECT_PREAMBLE_DURATION = currentChannelModel.getParameterDoubleValue(ChannelModel.Parameter.captureEffectPreambleDuration);
      }
    });
    
    /* Register plugins */
    sim.getGUI().registerPlugin(AreaViewer.class);
    sim.getGUI().registerPlugin(FormulaViewer.class);
    Visualizer.registerVisualizerSkin(MRMVisualizerSkin.class);
  }

  public void removed() {
    super.removed();

    /* Unregister plugins */
    sim.getGUI().unregisterPlugin(AreaViewer.class);
    sim.getGUI().unregisterPlugin(FormulaViewer.class);
    Visualizer.unregisterVisualizerSkin(MRMVisualizerSkin.class);

    currentChannelModel.deleteSettingsObserver(channelModelObserver);
  }
  
  private NoiseLevelListener noiseListener = new NoiseLevelListener() {
        public void noiseLevelChanged(NoiseSourceRadio radio, int signal) {
                updateSignalStrengths();
        };
  };
  public void registerRadioInterface(Radio radio, Simulation sim) {
        super.registerRadioInterface(radio, sim);
        
        if (WITH_NOISE && radio instanceof NoiseSourceRadio) {
                ((NoiseSourceRadio)radio).addNoiseLevelListener(noiseListener);
        }
  }
  public void unregisterRadioInterface(Radio radio, Simulation sim) {
        super.unregisterRadioInterface(radio, sim);

        if (WITH_NOISE && radio instanceof NoiseSourceRadio) {
                ((NoiseSourceRadio)radio).removeNoiseLevelListener(noiseListener);
        }
  }
  
  public MRMRadioConnection createConnections(final Radio sender) {
    MRMRadioConnection newConnection = new MRMRadioConnection(sender);
    final Position senderPos = sender.getPosition();

    /* TODO Cache potential destination in DGRM */
    /* Loop through all potential destinations */
    for (Radio recv: getRegisteredRadios()) {
      if (sender == recv) {
        continue;
      }

      /* Fail if radios are on different (but configured) channels */ 
      if (sender.getChannel() >= 0 &&
          recv.getChannel() >= 0 &&
          sender.getChannel() != recv.getChannel()) {
        continue;
      }
      final Radio recvFinal = recv;

      /* Calculate receive probability */
      TxPair txPair = new RadioPair() {
        public Radio getFromRadio() {
          return sender;
        }
        public Radio getToRadio() {
          return recvFinal;
        }
      };
      double[] probData = currentChannelModel.getProbability(
          txPair,
          -Double.MAX_VALUE /* TODO Include interference */
      );

      double recvProb = probData[0];
      double recvSignalStrength = probData[1];
      if (recvProb == 1.0 || random.nextDouble() < recvProb) {
        /* Yes, the receiver *may* receive this packet (it's strong enough) */
        if (!recv.isRadioOn()) {
          newConnection.addInterfered(recv);
          recv.interfereAnyReception();
        } else if (recv.isInterfered()) {
          if (WITH_CAPTURE_EFFECT) {
            /* XXX TODO Implement me:
             * If the new transmission is both stronger and the SFD has not
             * been received by the weaker transmission, then this new
             * transmission should be received.
             *
             * When this is implemented, also implement
             * RadioConnection.java:getReceptionStartTime()
             */

            /* Was interfered: keep interfering */
            newConnection.addInterfered(recv, recvSignalStrength);
          } else {
            /* Was interfered: keep interfering */
            newConnection.addInterfered(recv, recvSignalStrength);
          }
        } else if (recv.isTransmitting()) {
          newConnection.addInterfered(recv, recvSignalStrength);
        } else if (recv.isReceiving()) {
          /* Was already receiving: start interfering.
           * Assuming no continuous preambles checking */

          if (!WITH_CAPTURE_EFFECT) {
            newConnection.addInterfered(recv, recvSignalStrength);
            recv.interfereAnyReception();

            /* Interfere receiver in all other active radio connections */
            for (RadioConnection conn : getActiveConnections()) {
              if (conn.isDestination(recv)) {
                conn.addInterfered(recv);
              }
            }
          } else {
            /* CAPTURE EFFECT */
            double currSignal = recv.getCurrentSignalStrength();
            /* Capture effect: recv-radio is already receiving.
             * Are we strong enough to interfere? */

            if (recvSignalStrength < currSignal - CAPTURE_EFFECT_THRESHOLD /* config */) {
              /* No, we are too weak */
            } else {
              /* New signal is strong enough to either interfere with ongoing transmission,
               * or to be received/captured */
              long startTime = newConnection.getReceptionStartTime();
              boolean interfering = (sim.getSimulationTime()-startTime) >= CAPTURE_EFFECT_PREAMBLE_DURATION; /* us */
              if (interfering) {
                newConnection.addInterfered(recv, recvSignalStrength);
                recv.interfereAnyReception();

                /* Interfere receiver in all other active radio connections */
                for (RadioConnection conn : getActiveConnections()) {
                  if (conn.isDestination(recv)) {
                    conn.addInterfered(recv);
                  }
                }
              } else {
                /* XXX Warning: removing destination from other connections */
                for (RadioConnection conn : getActiveConnections()) {
                  if (conn.isDestination(recv)) {
                    conn.removeDestination(recv);
                  }
                }

                /* Success: radio starts receiving */
                newConnection.addDestination(recv, recvSignalStrength);
              }
            }
          }

        } else {
          /* Success: radio starts receiving */
          newConnection.addDestination(recv, recvSignalStrength);
        }
      } else if (recvSignalStrength > currentChannelModel.getParameterDoubleValue(Parameter.bg_noise_mean)) {
        /* The incoming signal is strong, but strong enough to interfere? */

        if (!WITH_CAPTURE_EFFECT) {
                newConnection.addInterfered(recv, recvSignalStrength);
                recv.interfereAnyReception();
        } else {
                /* TODO Implement new type: newConnection.addNoise()?
         * Currently, this connection will never disturb this radio... */
        }
      }

    }

    return newConnection;
  }

  public void updateSignalStrengths() {

    /* Reset: Background noise */
        double background = 
                currentChannelModel.getParameterDoubleValue((Parameter.bg_noise_mean));
    for (Radio radio : getRegisteredRadios()) {
      radio.setCurrentSignalStrength(background);
    }

    /* Active radio connections */
    RadioConnection[] conns = getActiveConnections();
    for (RadioConnection conn : conns) {
      for (Radio dstRadio : ((MRMRadioConnection) conn).getDestinations()) {
        double signalStrength = ((MRMRadioConnection) conn).getDestinationSignalStrength(dstRadio);
        if (conn.getSource().getChannel() >= 0 &&
            dstRadio.getChannel() >= 0 &&
            conn.getSource().getChannel() != dstRadio.getChannel()) {
          continue;
        }
        if (dstRadio.getCurrentSignalStrength() < signalStrength) {
          dstRadio.setCurrentSignalStrength(signalStrength);
        }
      }
    }

    /* Interfering/colliding radio connections */
    for (RadioConnection conn : conns) {
      for (Radio intfRadio : ((MRMRadioConnection) conn).getInterfered()) {
        double signalStrength = ((MRMRadioConnection) conn).getInterferenceSignalStrength(intfRadio);
        if (intfRadio.getCurrentSignalStrength() < signalStrength) {
                intfRadio.setCurrentSignalStrength(signalStrength);
        }
        if (conn.getSource().getChannel() >= 0 &&
            intfRadio.getChannel() >= 0 &&
            conn.getSource().getChannel() != intfRadio.getChannel()) {
          continue;
        }

        if (!intfRadio.isInterfered()) {
          /*logger.warn("Radio was not interfered: " + intfRadio);*/
                intfRadio.interfereAnyReception();
        }
      }
    }

    /* Check for noise sources */
    if (!WITH_NOISE) return;
    for (Radio noiseRadio: getRegisteredRadios()) {
      if (!(noiseRadio instanceof NoiseSourceRadio)) {
        continue;
      }
      final Radio fromRadio = noiseRadio;
      NoiseSourceRadio radio = (NoiseSourceRadio) noiseRadio;
      int signalStrength = radio.getNoiseLevel();
      if (signalStrength == Integer.MIN_VALUE) {
        continue;
      }

      /* Calculate how noise source affects surrounding radios */
      for (Radio affectedRadio : getRegisteredRadios()) {
        if (noiseRadio == affectedRadio) {
          continue;
        }

        /* Update noise levels */
        final Radio toRadio = affectedRadio;
        TxPair txPair = new RadioPair() {
          public Radio getFromRadio() {
            return fromRadio;
          }
          public Radio getToRadio() {
            return toRadio;
          }
        };
        double[] signalMeanVar = currentChannelModel.getReceivedSignalStrength(txPair);
        double signal = signalMeanVar[0];
        if (signal < background) {
          continue;
        }

        /* TODO Additive signals strengths? */
        /* TODO XXX Consider radio channels */
        /* TODO XXX Potentially interfere even when signal is weaker (~3dB)...
         * (we may alternatively just use the getSINR method...) */
        if (affectedRadio.getCurrentSignalStrength() < signal) {
          affectedRadio.setCurrentSignalStrength(signal);

          /* TODO Interfere with radio connections? */
          if (affectedRadio.isReceiving() && !affectedRadio.isInterfered()) {
            for (RadioConnection conn : conns) {
              if (conn.isDestination(affectedRadio)) {
                /* Intefere with current reception, mark radio as interfered */
                conn.addInterfered(affectedRadio);
                if (!affectedRadio.isInterfered()) {
                  affectedRadio.interfereAnyReception();
                }
              }
            }
          }
        }
      }
    }
  }

  public Collection<Element> getConfigXML() {
    return currentChannelModel.getConfigXML();
  }

  public boolean setConfigXML(Collection<Element> configXML,
      boolean visAvailable) {
    return currentChannelModel.setConfigXML(configXML);
  }


  // -- MRM specific methods --

  /**
   * Adds an observer which is notified when this radio medium has
   * changed settings, such as added or removed radios.
   *
   * @param obs New observer
   */
  public void addSettingsObserver(Observer obs) {
    settingsObservable.addObserver(obs);
  }

  /**
   * Deletes an earlier registered setting observer.
   *
   * @param obs Earlier registered observer
   */
  public void deleteSettingsObserver(Observer obs) {
    settingsObservable.deleteObserver(obs);
  }

  /**
   * @return Number of registered radios.
   */
  public int getRegisteredRadioCount() {
        /* TODO Expensive operation */
    return getRegisteredRadios().length;
  }

  /**
   * Returns radio at given index.
   *
   * @param index Index of registered radio.
   * @return Radio at given index
   */
  public Radio getRegisteredRadio(int index) {
    return getRegisteredRadios()[index];
  }

  /**
   * Returns the current channel model object, responsible for
   * all probability and transmission calculations.
   *
   * @return Current channel model
   */
  public ChannelModel getChannelModel() {
    return currentChannelModel;
  }

  class SettingsObservable extends Observable {
    private void notifySettingsChanged() {
      setChanged();
      notifyObservers();
    }
  }

  class MRMRadioConnection extends RadioConnection {
    private Hashtable<Radio, Double> signalStrengths = new Hashtable<Radio, Double>();

    public MRMRadioConnection(Radio sourceRadio) {
      super(sourceRadio);
    }

    public void addDestination(Radio radio, double signalStrength) {
      signalStrengths.put(radio, signalStrength);
      addDestination(radio);
    }

    public void addInterfered(Radio radio, double signalStrength) {
      signalStrengths.put(radio, signalStrength);
      addInterfered(radio);
    }

    public double getDestinationSignalStrength(Radio radio) {
        if (signalStrengths.get(radio) == null) {
                return Double.MIN_VALUE;
        }
      return signalStrengths.get(radio);
    }

    public double getInterferenceSignalStrength(Radio radio) {
        if (signalStrengths.get(radio) == null) {
                return Double.MIN_VALUE;
        }
      return signalStrengths.get(radio);
    }
  }

}
