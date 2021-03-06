package ca.mcgill.ecse211.ziplineproject;

import lejos.hardware.ev3.LocalEV3;
import lejos.hardware.lcd.TextLCD;
import lejos.hardware.motor.EV3LargeRegulatedMotor;
import lejos.hardware.port.Port;
import lejos.hardware.port.SensorPort;
import lejos.hardware.sensor.EV3ColorSensor;
import lejos.hardware.sensor.SensorModes;
import lejos.robotics.SampleProvider;
import ca.mcgill.ecse211.ziplineproject.Main.TeamColor;
import lejos.hardware.Button;
import lejos.hardware.Sound;

/**
 * This class is responsible for light localization using the color sensor.
 * 
 * @author Chaoyi Liu
 */
public final class LightLocalizer {

    private static double WHEEL_RADIUS = Main.WHEEL_RADIUS;
    private static double TRACK = Main.TRACK;
    private static final int FORWARD_SPEED = Main.FWD_SPEED;
    private static final int ROTATE_SPEED = Main.ROTATE_SPEED;
    private static final int ACCELERATION = 150;
    private static final int FORWARD_DISTANCE = 15;
    private static final double sensorDis = -12; // -4
    private static final double TILE = Main.TILE;
    private static final double BOARD_SIZE = Main.BOARD_SIZE;
    
    private double intensity;
    private static int startCorner;
    private static int x0;
    private static int y0;
    private static int xc;
    private static int yc; 

    private EV3LargeRegulatedMotor leftMotor = Main.leftMotor;
    private EV3LargeRegulatedMotor rightMotor = Main.rightMotor;
    private EV3LargeRegulatedMotor traverseMotor=Main.traverseMotor;
    private EV3ColorSensor colorSensor = Main.cSensor;

    private Odometer odometer = Main.odometer;
    private Navigation navigation = Main.navigation;

    private SampleProvider lightIntensity;
    private int sampleSize;
    private float[] csData;
    public int type; // type 0 is the first iteration, type 1 before zipline (normal), type 2 after zipline
    

    /*
     * LightLocalizer Constructor
     */
    public LightLocalizer() {
        this.lightIntensity = colorSensor.getRedMode();
        this.sampleSize = lightIntensity.sampleSize();
        this.csData = new float[sampleSize];
        setZiplineCoordinates();
        setStartCorner();
    }

    /**
     * Controller method for LightLocalization
     */
    public void runLightLocalization() {
        if(type==0) {
            doLightLocalizationBegin();
            doLightLocalization(1, 1);
            navigation.turnTo(0);           
            resetAccordingToCorner();
            
        }
        else if(type==1){
            doLightLocalization(x0, y0); // TODO Change according to starting corner
        }
        else if(type==2){
            driveForward();
            while (true) {
                if (hitGridLine()) {
                    Sound.beep();
                    break;
                }
            }
            
            // stop the motors
            stopMotor(); // Ok
            driveForward(-8,false);
            traverseMotor.stop();
            
            /* Other end of the zipline * can be calculated using trigonometry ☺ 
             * 
             *  unknownX = (5*TILE/p)*dx
             *  
             *     * ____
             *       \   |
             *  Zipln \  | unknownY = (5*TILE/p)*dy
             *  4*TILE \ |
             *          \|
             *           |\ p = √(dx²+dy²)
             *        dy |_\
             *            dx
             */
            double unknownX = Main.zc_g_x + 5*(Main.zc_g_x-Main.zo_g_x)/
                    Math.sqrt((Main.zc_g_x-Main.zo_g_x) * (Main.zc_g_x-Main.zo_g_x)
                            +(Main.zc_g_y-Main.zo_g_y) * (Main.zc_g_y-Main.zo_g_y)); // In TILE, not cm
            
            double unknownY = Main.zc_g_y + 5*(Main.zc_g_y-Main.zo_g_y)/
                    Math.sqrt((Main.zc_g_y-Main.zo_g_y) * (Main.zc_g_y-Main.zo_g_y)
                            +(Main.zc_g_y-Main.zo_g_y) * (Main.zc_g_y-Main.zo_g_y));// In TILE, not cm
            
            /* Terenary operator, similar to a spreadsheet IF statement. Syntax:
             * result = Condition? resultIfTrue : resultIfFalse 
             * We are performing this operation to avoid truncating too much when casting to an int,
             * so for example 3.99 will be 4 and not 3*/
            int redApproachX = (Math.abs(unknownX-Math.floor(unknownX))<0.5)? (int)unknownX: (int)unknownX+1;
            int redApproachY = (Math.abs(unknownY-Math.floor(unknownY))<0.5)? (int)unknownY: (int)unknownY+1;
            doLightLocalization(redApproachX, redApproachY); 
            stopMotor();
            odometer.setX(redApproachX*TILE);
            odometer.setY(redApproachY*TILE);
            Sound.beepSequence();
            Sound.beepSequence();
            
            /*navigation.travelTo(Main.sr_ur_x, Main.sr_ur_y); // TODO Change according to starting corner
            Sound.beepSequenceUp();
            Sound.beepSequenceUp();
            stopMotor();
            doLightLocalization(Main.sr_ur_x, Main.sr_ur_y);  // TODO Change according to starting corner
            stopMotor();*/
        } // end if(type==2)
    
    }

    /**
     * Prepare to do a light localization
     */
    public void doLightLocalizationBegin() {
      
        navigation.turnTo(45);
        driveForward(20,false);

    }

    
    /**
     * LightLocalization method, which is suitables for localizing at any point 
     * on the Panel. The arguments <i>x</i> and <i>y</i> are the approximate coordinates 
     * of the robot's location.
     * 
     * @param x <i>x</i> coordinate
     * @param y <i>y</i> coordinate
     */
    public void doLightLocalization(int x, int y) {
        
        setAcce(2500);
        // turn 360 degrees
        clockwise(360, true);

        // record a heading when detect a grid line
        double[] heading = new double[4];
        int i = 0;
        while (leftMotor.isMoving() && i < 4) {
            if (hitGridLine()) {               
                heading[i] = odometer.getTheta() * 180 / Math.PI;
                Sound.playNote(Sound.PIANO, 3 * (180 + 60 * i), 450);
                i++;
            }
        }

        // if detect less than 4 grid line, the location is not good
        // and need to move to a better location
        if (i < 4) {
            Sound.beepSequence(); // downwards sequence to indicate failure
            // move to a better location, and do it again
            if (i == 0 || i == 1) {
                // the robot locate at the center of a block
                // so we drive forward until we find a grid line
                driveForward();

                while (true) {
                    if (hitGridLine()) {
                        stopMotor();
                        Sound.beep();
                        break;
                    }
                }
                driveBackABit();

            } else if (i == 2) {
                // the robot locates on a line
                // so we follow the line to find a point             
                navigation.turnTo(heading[0]);
                
                driveForward(-15, false);
            } else if (i == 3) {
                double[] angle = new double[3];
                angle = getAngles(heading, angle);
                double MinAng = Math.min(angle[0], Math.min(angle[1], angle[2]));
                double directionAngle = findNewDirection(heading, angle, MinAng);
                // here turnTo method go to the reverse direction, so I just
                // drive backwards
                navigation.turnTo(directionAngle);

                driveForward(-10, false);
            }
            // recursively call doLightLocalization when not all 4 points detected
            doLightLocalization(x, y);
            return;
        }

        // find the 4 angles between 4 headings and find the Minimum value
        // between them
        double[] angle = new double[4];
        angle = getAngles(heading, angle);
        double MinAng = Math.min(angle[0], Math.min(angle[1], Math.min(angle[2], angle[3])));

        // if there is an angle too small (less than 40 degrees),
        // then the location of robot is not good.
        if (MinAng < 40) {
            Sound.beepSequence();
            Sound.beepSequence();
            // move to a better location, and do it again
            // here we move to the direction of the smallest angle
            double directionAngle = findNewDirection(heading, angle, MinAng);
            // here turnTo method go to the reverse direction, so I just drive
            // backwards
            navigation.turnTo(directionAngle);
            driveForward(-10, false);
            // recursively call doLightLocalization
            doLightLocalization(x, y);
            return;
        }

        // Now that the location is good, do the actual light localization
        // first calculate according to headings
        double theta1 = getAngleBetweenHeadings(heading[1], heading[3]);
        double direction1 = calculateAverageAngle(heading[1], heading[3]);
        double distance1 = sensorDis * Math.cos((Math.PI * theta1 / 180) / 2);

        double theta2 = getAngleBetweenHeadings(heading[0], heading[2]);
        double direction2 = calculateAverageAngle(heading[0], heading[2]);
        double distance2 = sensorDis * Math.cos((Math.PI * theta2 / 180) / 2);

        if (Main.printToConsole)
            System.out.println("dir1: " + direction1 + ", dir2: " + direction2);

        // do the localization
        navigation.turnTo(direction1);
        driveForward(distance1, false);
        navigation.turnTo(direction2);
        driveForward(distance2, false);
        Sound.beepSequenceUp();

        // find the actual location x,y
        double currentX = findClosestCoordinate(odometer.getX(), x * TILE);
        double currentY = findClosestCoordinate(odometer.getY(), y * TILE);
        // set odometer
        odometer.setX(currentX);
        odometer.setY(currentY);
 
        double startTime = System.currentTimeMillis();
        
        //setAcce(2500);
        // turn ccw
        clockwise();
     
        // when a grid line is detected,
        // stop and set Theta to the closest direction between 4 directions
        while (leftMotor.isMoving() && rightMotor.isMoving() && (System.currentTimeMillis() < startTime + 10000)) {
            if (hitGridLine()) {
                stopMotor();
                break;
            }
        }
        Sound.playNote(Sound.PIANO, 600, 300);
        // correction by offset
        counterclockwise(7, false);


        // calibration
        double currentTheta = odometer.getTheta() * 180 / Math.PI;
        int calibration = setToClosestTheta(currentTheta);
        odometer.setTheta(calibration * Math.PI / 180);
        Sound.playNote(Sound.PIANO, 800, 300);

    } // end doLightLocalization

    
    
    /**
     * On a panel, find the closest crossing point's coordinate value 
     * according to current value.
     * @param currentVal The current value
     * @param estimateVal The estimated value
     * @return The closest coordinate
     */
    private double findClosestCoordinate(double currentVal, double estimateVal) {
        double calibratedVal=estimateVal;
        while(Math.abs(calibratedVal-currentVal)> TILE/2){
            if(calibratedVal>currentVal)
                calibratedVal=calibratedVal-TILE;
            else
                calibratedVal=calibratedVal+TILE;
        }   
        return calibratedVal;
    }

    /**
     * When the robot locates exactly on a crossing point,
     * we turn the robot until we detect a grid line.
     * Now theta can have 4 possibilities: 0, 90, 180, 360.
     * This method helps figure out which to choose between these 4 directions.
     * It returns in degree.
     * @param currTheta
     * @return
     */
    private int setToClosestTheta(double currTheta) {
        double[] error = new double[4];
        double closestValue = 360;
        int closest = 0;
        for (int i = 0; i < 4; i++) {
            error[i] = Math.abs(currTheta - i * 90);
            if (closestValue > error[i]) {
                closestValue = error[i];
                closest = i * 90; // 2 is for the delay of stop
            }
        }
        if (closestValue > 45) {
            if (Math.abs(currTheta - 360) < 40)
                closest = 0;
        }
        return closest;
    }

    /**
     *   When we detect 4 grid lines but there is an angle smaller
     * than a threshold, the location of robot may not 
     * be optimal for localization. Moving to the direction of the smallest angle can increase
     * the angle value. This method helps us find this direction.
     * 
     * @param heading
     * @param Angle
     * @param MinAng
     * @return New direction to use, in degrees
     */
    private double findNewDirection(double[] heading, double[] Angle, double MinAng) {
        double direction = 0;
        for (int i = 0; i < Angle.length; i++) {
            if (Angle[i] == MinAng) {
                if (i !=  Angle.length-1) {
                    direction = calculateAverageAngle(heading[i], heading[i + 1]);
                } else {
                    direction = calculateAverageAngle(heading[i], heading[0]);
                }
            }
        }
        return direction;
    }

    /**
     * Given two angles in degrees, find the average angle between them. Wrap-around is
     * take into consideration here.
     *
     * @param a The first angle, in degrees
     * @param b The second angle, in degrees
     * @return The average angle in degrees
     */
    private double calculateAverageAngle(double a, double b) {
        double average;
        if (a <= b) // normal condition
            average = (a + b) / 2;
        else { // when the second angle passes 0
            average = ((a - 360) + b) / 2;
            if (average < 0) {
                average = average + 360;
            }
        }
        return average;
    }

    /**
     * Given an array of headings, store the angle of neighboring headings in another array
     * @param heading Input heading array
     * @param angle Input angle
     * @return An array of neighboring headings
     */
    private double[] getAngles(double[] heading, double[] angle) {
        for (int i = 0; i < angle.length; i++) {
            if (i != angle.length-1) {
                angle[i] = getAngleBetweenHeadings(heading[i], heading[i + 1]);
            } else {
                angle[i] = getAngleBetweenHeadings(heading[i], heading[0]);
            }
        }
        return angle;
    }
    
    
    /**
     * Calculate the angle between 2 headings.
     * Wrap around take into consideration here.
     * It returns in degree.
     * @param a The first angle, in degrees
     * @param b The second angle, in degrees
     * @return The average angle in degrees
     */
    private double getAngleBetweenHeadings(double a, double b) {
        double angle;
        if (a <= b) // normal condition
            angle = b - a;
        else // when the second angle passes 0
            angle = b - (a - 360);
        return angle;
    }

    /**
     * Stop at a gridline
     */
    public void stopAtGridline() {
        while (true) {
            if (hitGridLine()) {
                Sound.beep();
                break;
            }
        }
    }

    /**
     * Reset the odometer based on the starting corner
     */
    private void resetAccordingToCorner() {
        if (startCorner == 0) {
            odometer.setX(1 * TILE);
            odometer.setY(1 * TILE);
        } else if (startCorner == 1) {
            // reset x,y,theta
            odometer.setX((BOARD_SIZE-1) * TILE);
            odometer.setY(1 * TILE);
            odometer.setTheta(Math.PI * 3 / 2);
        } else if (startCorner == 2) {
            // reset x,y,theta
            odometer.setX((BOARD_SIZE-1) * TILE);
            odometer.setY((BOARD_SIZE-1) * TILE);
            odometer.setTheta(Math.PI);
        } else if (startCorner == 3) {
            // reset x,y,theta
            odometer.setX(1 * TILE);
            odometer.setY((BOARD_SIZE-1) * TILE);
            odometer.setTheta(Math.PI / 2);
        }

    }

    /**
     * Make both motors go forward for a certain distance
     * 
     * @param distance The distance to move forward
     * @param condition Controls whether the next instruction(s)
     * should be executed when this rotation hasn't ended
     */
    private void driveForward(double distance, boolean condition) {
        Navigation.setAcceleration(500);
        setForwardSpeed();
        leftMotor.rotate(convertDistance(WHEEL_RADIUS, distance*0.6), true);
        rightMotor.rotate(convertDistance(WHEEL_RADIUS, distance*0.6), condition);
       
        Navigation.setAcceleration(2500);
        try{Thread.sleep(200);}catch(Exception e){}
    }

    /**
     * Make robot rotate clockwise for a certain angle
     * 
     * @param theta Number of degrees to turn 
     * @param condition Controls whether the next instruction(s)
     * should be executed when this rotation hasn't ended.
     */
    private void clockwise(double theta, boolean condition) {
        setRotateSpeed();
        leftMotor.rotate(convertAngle(WHEEL_RADIUS, TRACK, theta), true);
        rightMotor.rotate(-convertAngle(WHEEL_RADIUS, TRACK, theta), condition);
        try{Thread.sleep(200);}catch(Exception e){}
    }

    /**
     * Make robot rotate counterclockwise for a certain angle
     * 
     * @param theta Number of degrees to turn 
     * @param condition Controls whether the next instruction(s)
     * should be executed when this rotation hasn't ended.
     */
    private void counterclockwise(double theta, boolean condition) {
     
        setRotateSpeed();
        leftMotor.rotate(-convertAngle(WHEEL_RADIUS, TRACK, theta), true);
        rightMotor.rotate(convertAngle(WHEEL_RADIUS, TRACK, theta), condition);
        try{Thread.sleep(200);}catch(Exception e){}
    }

    /**
     * Returns true if a grid line is crossed
     */
    boolean hitGridLine() {
        lightIntensity.fetchSample(csData, 0);
        intensity = csData[0];
        return intensity < 0.3;
    }

    /**
     * Make robot rotate clockwise
     */
    public void clockwise() {
        setRotateSpeed();
        leftMotor.forward();
        rightMotor.backward();
    }

    /**
     * Make robot rotate counterclockwise
     */
    public void counterclockwise() {
        setRotateSpeed();
        leftMotor.backward();
        rightMotor.forward();
    }

    // TODO Remove these methods later
    /**
     * Make robot stop
     */
    private void stopMotor() {
        leftMotor.stop(true);
        rightMotor.stop(false);

        try{Thread.sleep(200);}catch(Exception e){}
    }

    /**
     * Set acceleration of the motors
     */
    private void setAcce() {
        leftMotor.setAcceleration(ACCELERATION);
        rightMotor.setAcceleration(ACCELERATION);
    }
    
    /**
     * Set acceleration of the motors
     */
    private void setAcce(int acceleration) {
        leftMotor.setAcceleration(acceleration);
        rightMotor.setAcceleration(acceleration);
    }

    /**
     * Set rotation speed of the motors
     */
    private void setRotateSpeed() {
        leftMotor.setSpeed(ROTATE_SPEED);
        rightMotor.setSpeed(ROTATE_SPEED);
    }

    /**
     * Set forward speed of the motors
     */
    private void setForwardSpeed() {
        leftMotor.setSpeed(FORWARD_SPEED);
        rightMotor.setSpeed(FORWARD_SPEED);
    }

    /**
     * Make both motors go forward
     */
    private void driveForward() {
        setForwardSpeed();
        leftMotor.forward();
        rightMotor.forward();
    }

    /**
     * Make both motors drive back a bit.
     * The next instruction will be executed after this rotation ends.
     */
    private void driveBackABit() {
        setForwardSpeed();
        leftMotor.rotate(-convertDistance(WHEEL_RADIUS, FORWARD_DISTANCE), true);
        rightMotor.rotate(-convertDistance(WHEEL_RADIUS, FORWARD_DISTANCE), false);
        try{Thread.sleep(200);}catch(Exception e){}
    }

    /**
     * Convert to wheel rotations based on the wheel radius and travel distance
     * 
     * @param radius Wheel radius of the regular EV3 wheels
     * @param distance Desired distance to travel
     * @return Number of degrees that motor must rotate to travel the required distance
     */
    private static int convertDistance(double radius, double distance) {
        return (int) ((180.0 * distance) / (Math.PI * radius));
    }

    /**
     * Calls convertDistance to get wheel rotations based on the wheel radius, width, and angle
     * 
     * @param radius Wheel radius of the regular EV3 wheels
     * @param width Width of the robot (TRACK)
     * @param angle Angle that defines the distance
     * @return Number of degrees that motor must rotate to travel the required distance
     */
    private static int convertAngle(double radius, double width, double angle) {
        return convertDistance(radius, Math.PI * width * angle / 360.0);
    }
    
    private static void setZiplineCoordinates() {
            x0 = Main.zo_g_x;
            y0 = Main.zo_g_y;
            xc = Main.zc_g_x;
            yc = Main.zc_g_y;
    }
    
    private static void setStartCorner() { // TODO Wrong assumption
        if(Main.teamColor.equals(TeamColor.GREEN)) {
            startCorner = 1;
        } else { // RED
            startCorner = 3;
        }
    }

}
