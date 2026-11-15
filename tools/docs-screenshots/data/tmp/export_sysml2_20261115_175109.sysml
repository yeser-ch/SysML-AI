package DroneModel {
    part def Drone {
        part battery : Battery;
        part motors : Motor[4];
    }
    part def Battery { attribute capacity = 5000; }
    part def Motor;
    requirement def OperatingTime {
        doc /* The drone shall fly for at least 25 min on one battery charge. */
    }
    satisfy OperatingTime by Drone::battery;
}
