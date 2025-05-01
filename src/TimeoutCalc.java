public class TimeoutCalc {

    private double eRTT;
    private double eDEV = 0;
    private double timeOut = 5000.0;

    private final double alpha = 0.875;
    private final double beta = 0.75;
    private boolean firstPacket = true;

    public void updateRTT(double sampleRTT) {

        double sampleRTTMillis = sampleRTT/1000000;
        if (firstPacket) {
            eRTT = sampleRTTMillis;
            eDEV = 0;
            timeOut = 2 * eRTT;
            firstPacket = false;
        } else {
            double sRTT = sampleRTTMillis;
            double sDEV = Math.abs(sRTT - eRTT);

            eRTT = alpha * eRTT + (1 - alpha) * sRTT;
            eDEV = beta * eDEV + (1 - beta) * sDEV;
            timeOut = eRTT + 4 * eDEV;
        }
        //System.out.println("updated RTT" + this.getTimeOut());
    }

    public int getTimeOut(){
        return (int) Math.ceil(this.timeOut);
    }
}
