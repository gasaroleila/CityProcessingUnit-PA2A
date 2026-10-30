package dispatcher;

import dispatcher.policies.Policy;
import driver.Driver;
import trip.Trip;

import java.util.*;

import driver.Message;
import logbook.EventType;

public class Dispatcher {

    private final Driver driver;
    private final Policy policy;
    private Trip current;
    private final List<Trip> readyLine = new LinkedList<>();
    private final List<Trip> waitingList = new ArrayList<>();
    private final List<Trip> finished = new ArrayList<>();
    private int stretchStartTick;
    private int dispatchesSincePick;

    public Dispatcher(Driver driver, Policy policy) {
        this.driver = driver;
        this.policy = policy;
        driver.setDispatcher(this);
    }

    public void dispatch() {
        // dispatch ticks are non-driving ticks, so we subtract them to get actual driving time
        if (current != null) dispatchesSincePick++;
        int stretchLength = current != null ? driver.getClock().getTickNum() - stretchStartTick - dispatchesSincePick : 0;
        // tracks whether a context switch happened so we know to call setTrip
        boolean tripLeft = false;

        Message msg;
        while ((msg = driver.getMessageQueue().poll()) != null) {
            switch (msg.type()) {
                case STOP_SIGN -> { // save progress, move trip back to ready line

                    current.saveProgress(driver.getNextAddress(), stretchLength);
                    readyLine.add(current);
                    current = null;
                    tripLeft = true;
                }
                case RED_LIGHT -> { // save progress, move trip to waiting list

                    current.saveProgress(driver.getNextAddress(), stretchLength);
                    waitingList.add(current);
                    current = null;
                    tripLeft = true;
                }
                case GREEN_LIGHT -> { // move matching trip from waiting list to ready line

                    Iterator<Trip> it = waitingList.iterator();
                    while (it.hasNext()) {
                        Trip t = it.next();
                        if (t.getVehicle().id() == msg.vehicleId()) {
                            it.remove();
                            readyLine.add(t);
                            break;
                        }
                    }
                }
                case DESTINATION -> { // trip is done, log it and move to finished

                    current.saveProgress(driver.getNextAddress(), stretchLength);
                    driver.getLogbook().record(current.getVehicle(), EventType.TRIP_COMPLETE);
                    finished.add(current);
                    current = null;
                    tripLeft = true;
                }
                case TIMER -> {
                    // if current already left (e.g. STOP_SIGN earlier this tick), do nothing
                    if (current != null) {
                        current.saveProgress(driver.getNextAddress(), stretchLength);
                        readyLine.add(current);
                        current = null;
                        tripLeft = true;
                    }
                }
            }
        }

        // pick next trip from ready line using the scheduling policy
        if (current == null && !readyLine.isEmpty()) {
            current = policy.pickNext(Collections.unmodifiableList(readyLine));
            readyLine.remove(current);
            if (policy.timeSlice() > 0) driver.startTimer(policy.timeSlice());
            stretchStartTick = driver.getClock().getTickNum();
            dispatchesSincePick = 0;
            tripLeft = true;
        }

        // only update the driver if a context switch actually happened
        if (tripLeft) driver.setTrip(current);
    }

    public Trip getCurrent() {
        return current;
    }

    public void addNewTrip(Trip trip) {
        driver.getLogbook().record(trip.getVehicle(), EventType.TRIP_START);
        readyLine.add(trip);
    }

    public int getNumTrips() {
        return (current != null ? 1 : 0) + readyLine.size() + waitingList.size();
    }

    public int getReadyLineSize() {
        return readyLine.size();
    }

    public int getWaitingListSize() {
        return waitingList.size();
    }

    public List<Trip> getFinished() {
        return finished;
    }
}
