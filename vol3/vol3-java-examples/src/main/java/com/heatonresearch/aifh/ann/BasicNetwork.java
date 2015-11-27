package com.heatonresearch.aifh.ann;

import com.heatonresearch.aifh.AIFHError;
import com.heatonresearch.aifh.ann.activation.ActivationFunction;
import com.heatonresearch.aifh.ann.activation.ActivationLinear;
import com.heatonresearch.aifh.ann.activation.ActivationSigmoid;
import com.heatonresearch.aifh.ann.activation.ActivationTANH;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BasicNetwork {
    /**
     * The default bias activation.
     */
    public static final double DEFAULT_BIAS_ACTIVATION = 1.0;

    /**
     * The value that indicates that there is no bias activation.
     */
    public static final double NO_BIAS_ACTIVATION = 0.0;

    /**
     * The number of input neurons in this network.
     */
    private int inputCount;

    /**
     * The number of neurons in each of the layers.
     */
    private int[] layerCounts;

    /**
     * The dropout rate for each layer.
     */
    private double[] layerDropoutRates;

    /**
     * The number of context neurons in each layer. These context neurons will
     * feed the next layer.
     */
    private int[] layerContextCount;

    /**
     * The number of neurons in each layer that are actually fed by neurons in
     * the previous layer. Bias neurons, as well as context neurons, are not fed
     * from the previous layer.
     */
    private int[] layerFeedCounts;

    /**
     * An index to where each layer begins (based on the number of neurons in
     * each layer).
     */
    private int[] layerIndex;

    /**
     * The outputs from each of the neurons.
     */
    private double[] layerOutput;

    /**
     * The sum of the layer, before the activation function is applied, producing the layerOutput.
     */
    private double[] layerSums;

    /**
     * The number of output neurons in this network.
     */
    private int outputCount;

    /**
     * The index to where the weights that are stored at for a given layer.
     */
    private int[] weightIndex;

    /**
     * The weights for a neural network.
     */
    private double[] weights;

    /**
     * The activation types.
     */
    private ActivationFunction[] activationFunctions;

    /**
     * The context target for each layer. This is how the backwards connections
     * are formed for the recurrent neural network. Each layer either has a
     * zero, which means no context target, or a layer number that indicates the
     * target layer.
     */
    private int[] contextTargetOffset;

    /**
     * The size of each of the context targets. If a layer's contextTargetOffset
     * is zero, its contextTargetSize should also be zero. The contextTargetSize
     * should always match the feed count of the targeted context layer.
     */
    private int[] contextTargetSize;

    /**
     * The bias activation for each layer. This is usually either 1, for a bias,
     * or zero for no bias.
     */
    private double[] biasActivation;

    /**
     * The layer that training should begin on.
     */
    private int beginTraining;

    /**
     * The layer that training should end on.
     */
    private int endTraining;

    /**
     * Does this network have some connections disabled.
     */
    private boolean isLimited;

    /**
     * The limit, under which, all a cconnection is not considered to exist.
     */
    private double connectionLimit;

    /**
     * True if the network has context.
     */
    private boolean hasContext;

    /**
     * Default constructor.
     */
    public BasicNetwork() {
        this.layerDropoutRates = new double[0];
    }

    /**
     * Create a flat network from an array of layers.
     *
     * @param layers
     *            The layers.
     */
    public BasicNetwork(final BasicLayer[] layers) {
        init(layers,false);
    }
    public BasicNetwork(final BasicLayer[] layers, boolean dropout) {
        init(layers,dropout);
    }

    /**
     * Construct a flat neural network.
     *
     * @param input
     *            Neurons in the input layer.
     * @param hidden1
     *            Neurons in the first hidden layer. Zero for no first hidden
     *            layer.
     * @param hidden2
     *            Neurons in the second hidden layer. Zero for no second hidden
     *            layer.
     * @param output
     *            Neurons in the output layer.
     * @param tanh
     *            True if this is a tanh activation, false for sigmoid.
     */
    public BasicNetwork(final int input, final int hidden1, final int hidden2,
                       final int output, final boolean tanh) {

        final ActivationFunction linearAct = new ActivationLinear();
        BasicLayer[] layers;
        final ActivationFunction act = tanh ? new ActivationTANH()
                : new ActivationSigmoid();

        if ((hidden1 == 0) && (hidden2 == 0)) {
            layers = new BasicLayer[2];
            layers[0] = new BasicLayer(linearAct, input,
                    BasicNetwork.DEFAULT_BIAS_ACTIVATION);
            layers[1] = new BasicLayer(act, output,
                    BasicNetwork.NO_BIAS_ACTIVATION);
        } else if ((hidden1 == 0) || (hidden2 == 0)) {
            final int count = Math.max(hidden1, hidden2);
            layers = new BasicLayer[3];
            layers[0] = new BasicLayer(linearAct, input,
                    BasicNetwork.DEFAULT_BIAS_ACTIVATION);
            layers[1] = new BasicLayer(act, count,
                    BasicNetwork.DEFAULT_BIAS_ACTIVATION);
            layers[2] = new BasicLayer(act, output,
                    BasicNetwork.NO_BIAS_ACTIVATION);
        } else {
            layers = new BasicLayer[4];
            layers[0] = new BasicLayer(linearAct, input,
                    BasicNetwork.DEFAULT_BIAS_ACTIVATION);
            layers[1] = new BasicLayer(act, hidden1,
                    BasicNetwork.DEFAULT_BIAS_ACTIVATION);
            layers[2] = new BasicLayer(act, hidden2,
                    BasicNetwork.DEFAULT_BIAS_ACTIVATION);
            layers[3] = new BasicLayer(act, output,
                    BasicNetwork.NO_BIAS_ACTIVATION);
        }

        this.isLimited = false;
        this.connectionLimit = 0.0;

        init(layers,false);
    }

    /**
     * Clear any connection limits.
     */
    public void clearConnectionLimit() {
        this.connectionLimit = 0.0;
        this.isLimited = false;
    }

    /**
     * Clear any context neurons.
     */
    public void clearContext() {
        int index = 0;

        for (int i = 0; i < this.layerIndex.length; i++) {

            final boolean hasBias = (this.layerContextCount[i] + this.layerFeedCounts[i]) != this.layerCounts[i];

            // fill in regular neurons
            Arrays.fill(this.layerOutput, index, index+this.layerFeedCounts[i], 0);
            index += this.layerFeedCounts[i];

            // fill in the bias
            if (hasBias) {
                this.layerOutput[index++] = this.biasActivation[i];
            }

            // fill in context
            Arrays.fill(this.layerOutput, index, index+this.layerContextCount[i], 0);
            index += this.layerContextCount[i];
        }
    }

    /**
     * Calculate the output for the given input.
     *
     * @param input
     *            The input.
     * @param output
     *            Output will be placed here.
     */
    public void compute(final double[] input, final double[] output) {
        final int sourceIndex = this.layerOutput.length
                - this.layerCounts[this.layerCounts.length - 1];

        System.arraycopy(input, 0, this.layerOutput, sourceIndex,
                this.inputCount);

        for (int i = this.layerIndex.length - 1; i > 0; i--) {
            computeLayer(i);
        }

        // update context values
        final int offset = this.contextTargetOffset[0];

        System.arraycopy(this.layerOutput, 0, layerOutput,
                offset, this.contextTargetSize[0]);

        System.arraycopy(this.layerOutput, 0, output, 0, this.outputCount);
    }

    public double[] compute(double[] input) {
        double[] output = new double[getOutputCount()];
        compute(input,output);
        return output;
    }

    /**
     * Calculate a layer.
     *
     * @param currentLayer
     *            The layer to calculate.
     */
    protected void computeLayer(final int currentLayer) {

        final int inputIndex = this.layerIndex[currentLayer];
        final int outputIndex = this.layerIndex[currentLayer - 1];
        final int inputSize = this.layerCounts[currentLayer];
        final int outputSize = this.layerFeedCounts[currentLayer - 1];
        final double dropoutRate;
        if(this.layerDropoutRates.length > currentLayer - 1) {
            dropoutRate = this.layerDropoutRates[currentLayer - 1];
        } else {
            dropoutRate = 0;
        }

        int index = this.weightIndex[currentLayer - 1];

        final int limitX = outputIndex + outputSize;
        final int limitY = inputIndex + inputSize;

        // weight values
        for (int x = outputIndex; x < limitX; x++) {
            double sum = 0;
            for (int y = inputIndex; y < limitY; y++) {
                sum += this.weights[index++] * this.layerOutput[y] * (1 - dropoutRate);
            }
            this.layerSums[x] = sum;
            this.layerOutput[x] = sum;
        }

        this.activationFunctions[currentLayer - 1].activationFunction(
                this.layerOutput, outputIndex, outputSize);

        // update context values
        final int offset = this.contextTargetOffset[currentLayer];

        System.arraycopy(this.layerOutput, outputIndex,
                this.layerOutput, offset, this.contextTargetSize[currentLayer]);
    }

    /**
     * @return The activation functions.
     */
    public ActivationFunction[] getActivationFunctions() {
        return this.activationFunctions;
    }

    /**
     * @return the beginTraining
     */
    public int getBeginTraining() {
        return this.beginTraining;
    }

    /**
     * @return The bias activation.
     */
    public double[] getBiasActivation() {
        return this.biasActivation;
    }

    /**
     * @return the connectionLimit
     */
    public double getConnectionLimit() {
        return this.connectionLimit;
    }

    /**
     * @return The offset of the context target for each layer.
     */
    public int[] getContextTargetOffset() {
        return this.contextTargetOffset;
    }

    /**
     * @return The context target size for each layer. Zero if the layer does
     *         not feed a context layer.
     */
    public int[] getContextTargetSize() {
        return this.contextTargetSize;
    }

    /**
     * @return The length of the array the network would encode to.
     */
    public int getEncodeLength() {
        return this.weights.length;
    }

    /**
     * @return the endTraining
     */
    public int getEndTraining() {
        return this.endTraining;
    }

    /**
     * @return True if this network has context.
     */
    public boolean getHasContext() {
        return this.hasContext;
    }

    /**
     * @return The number of input neurons.
     */
    public int getInputCount() {
        return this.inputCount;
    }

    /**
     * @return The layer context count.
     */
    public int[] getLayerContextCount() {
        return this.layerContextCount;
    }

    /**
     * @return The number of neurons in each layer.
     */
    public int[] getLayerCounts() {
        return this.layerCounts;
    }

    /**
     * @return The number of neurons in each layer that are fed by the previous
     *         layer.
     */
    public int[] getLayerFeedCounts() {
        return this.layerFeedCounts;
    }

    /**
     * @return Indexes into the weights for the start of each layer.
     */
    public int[] getLayerIndex() {
        return this.layerIndex;
    }

    /**
     * @return The output for each layer.
     */
    public double[] getLayerOutput() {
        return this.layerOutput;
    }

    /**
     * @return The neuron count.
     */
    public int getNeuronCount() {
        int result = 0;
        for (final int element : this.layerCounts) {
            result += element;
        }
        return result;
    }

    /**
     * @return The number of output neurons.
     */
    public int getOutputCount() {
        return this.outputCount;
    }

    /**
     * @return The index of each layer in the weight and threshold array.
     */
    public int[] getWeightIndex() {
        return this.weightIndex;
    }

    /**
     * @return The index of each layer in the weight and threshold array.
     */
    public double[] getWeights() {
        return this.weights;
    }

    /**
     * Neural networks with only one type of activation function offer certain
     * optimization options. This method determines if only a single activation
     * function is used.
     *
     * @return The number of the single activation function, or -1 if there are
     *         no activation functions or more than one type of activation
     *         function.
     */
    public Class<?> hasSameActivationFunction() {
        final List<Class<?>> map = new ArrayList<Class<?>>();

        for (final ActivationFunction activation : this.activationFunctions) {
            if (!map.contains(activation.getClass())) {
                map.add(activation.getClass());
            }
        }

        if (map.size() != 1) {
            return null;
        } else {
            return map.get(0);
        }
    }

    /**
     * Construct a flat network.
     *
     * @param layers
     *            The layers of the network to create.
     */
    public void init(final BasicLayer[] layers, boolean dropout) {

        final int layerCount = layers.length;

        this.inputCount = layers[0].getCount();
        this.outputCount = layers[layerCount - 1].getCount();

        this.layerCounts = new int[layerCount];
        this.layerContextCount = new int[layerCount];
        this.weightIndex = new int[layerCount];
        this.layerIndex = new int[layerCount];
        if(dropout){
            this.layerDropoutRates = new double[layerCount];
        } else {
            this.layerDropoutRates = new double[0];
        }
        this.activationFunctions = new ActivationFunction[layerCount];
        this.layerFeedCounts = new int[layerCount];
        this.contextTargetOffset = new int[layerCount];
        this.contextTargetSize = new int[layerCount];
        this.biasActivation = new double[layerCount];

        int index = 0;
        int neuronCount = 0;
        int weightCount = 0;

        for (int i = layers.length - 1; i >= 0; i--) {

            final BasicLayer layer = layers[i];
            BasicLayer nextLayer = null;

            if (i > 0) {
                nextLayer = layers[i - 1];
            }

            this.biasActivation[index] = layer.getBiasActivation();
            this.layerCounts[index] = layer.getTotalCount();
            this.layerFeedCounts[index] = layer.getCount();
            this.layerContextCount[index] = layer.getContextCount();
            this.activationFunctions[index] = layer.getActivation();
            if(dropout)
            {
                this.layerDropoutRates[index] = layer.getDropoutRate();
            }

            neuronCount += layer.getTotalCount();

            if (nextLayer != null) {
                weightCount += layer.getCount() * nextLayer.getTotalCount();
            }

            if (index == 0) {
                this.weightIndex[index] = 0;
                this.layerIndex[index] = 0;
            } else {
                this.weightIndex[index] = this.weightIndex[index - 1]
                        + (this.layerCounts[index] * this.layerFeedCounts[index - 1]);
                this.layerIndex[index] = this.layerIndex[index - 1]
                        + this.layerCounts[index - 1];
            }

            int neuronIndex = 0;
            for (int j = layers.length - 1; j >= 0; j--) {
                if (layers[j].getContextFedBy() == layer) {
                    this.hasContext = true;
                    this.contextTargetSize[index] = layers[j].getContextCount();
                    this.contextTargetOffset[index] = neuronIndex
                            + (layers[j].getTotalCount() - layers[j]
                            .getContextCount());
                }
                neuronIndex += layers[j].getTotalCount();
            }

            index++;
        }

        this.beginTraining = 0;
        this.endTraining = this.layerCounts.length - 1;

        this.weights = new double[weightCount];
        this.layerOutput = new double[neuronCount];
        this.layerSums = new double[neuronCount];

        clearContext();
    }

    /**
     * @return the isLimited
     */
    public boolean isLimited() {
        return this.isLimited;
    }

    /**
     * Perform a simple randomization of the weights of the neural network
     * between -1 and 1.
     */
    public void randomize() {
        randomize(1, -1);
    }

    /**
     * Perform a simple randomization of the weights of the neural network
     * between the specified hi and lo.
     *
     * @param hi
     *            The network high.
     * @param lo
     *            The network low.
     */
    public void randomize(final double hi, final double lo) {
        for (int i = 0; i < this.weights.length; i++) {
            this.weights[i] = (Math.random() * (hi - lo)) + lo;
        }
    }

    /**
     * Set the activation functions.
     * @param af The activation functions.
     */
    public void setActivationFunctions(final ActivationFunction[] af) {
        this.activationFunctions = Arrays.copyOf(af, af.length);
    }

    /**
     * @param beginTraining
     *            the beginTraining to set
     */
    public void setBeginTraining(final int beginTraining) {
        this.beginTraining = beginTraining;
    }

    /**
     * Set the bias activation.
     * @param biasActivation The bias activation.
     */
    public void setBiasActivation(final double[] biasActivation) {
        this.biasActivation = biasActivation;
    }

    /**
     * @param endTraining
     *            the endTraining to set
     */
    public void setEndTraining(final int endTraining) {
        this.endTraining = endTraining;
    }

    /**
     * Set the hasContext property.
     * @param hasContext True if the network has context.
     */
    public void setHasContext(final boolean hasContext) {
        this.hasContext = hasContext;
    }

    /**
     * Set the input count.
     * @param inputCount The input count.
     */
    public void setInputCount(final int inputCount) {
        this.inputCount = inputCount;
    }



    /**
     * Set the output count.
     * @param outputCount The output count.
     */
    public void setOutputCount(final int outputCount) {
        this.outputCount = outputCount;
    }


    /**
     * @return the layerSums
     */
    public double[] getLayerSums() {
        return layerSums;
    }


    public double[] getLayerDropoutRates() {
        return layerDropoutRates;
    }

    public void setLayerDropoutRates(double[] layerDropoutRates) {
        this.layerDropoutRates = layerDropoutRates;
    }

    /**
     * Get the weight between the two layers.
     * @param fromLayer The from layer.
     * @param fromNeuron The from neuron.
     * @param toNeuron The to neuron.
     * @return The weight value.
     */
    public double getWeight(final int fromLayer,
                            final int fromNeuron,
                            final int toNeuron) {
        validateNeuron(fromLayer, fromNeuron);
        validateNeuron(fromLayer + 1, toNeuron);
        final int fromLayerNumber = this.layerContextCount.length - fromLayer - 1;
        final int toLayerNumber = fromLayerNumber - 1;

        if (toLayerNumber < 0) {
            throw new AIFHError(
                    "The specified layer is not connected to another layer: "
                            + fromLayer);
        }

        final int weightBaseIndex
                = this.weightIndex[toLayerNumber];
        final int count
                = this.layerCounts[fromLayerNumber];
        final int weightIndex = weightBaseIndex + fromNeuron
                + (toNeuron * count);

        return this.weights[weightIndex];
    }

    /**
     * Validate the the specified targetLayer and neuron are valid.
     * @param targetLayer The target layer.
     * @param neuron The target neuron.
     */
    public void validateNeuron(final int targetLayer, final int neuron) {
        if ((targetLayer < 0) || (targetLayer >= this.layerCounts.length)) {
            throw new AIFHError("Invalid layer count: " + targetLayer);
        }

        if ((neuron < 0) || (neuron >= getLayerTotalNeuronCount(targetLayer))) {
            throw new AIFHError("Invalid neuron number: " + neuron);
        }
    }

    /**
     * Get the total (including bias and context) neuron cont for a layer.
     * @param l The layer.
     * @return The count.
     */
    public int getLayerTotalNeuronCount(final int l) {
        final int layerNumber = this.layerCounts.length - l - 1;
        return this.layerCounts[layerNumber];
    }

    /**
     * Get the neuron count.
     * @param l The layer.
     * @return The neuron count.
     */
    public int getLayerNeuronCount(final int l) {
        final int layerNumber = this.layerCounts.length - l - 1;
        return this.layerFeedCounts[layerNumber];
    }

    public void addLayer(BasicLayer layer) {

    }

    public void finalizeStructure() {

    }

    public void reset() {

    }
}
