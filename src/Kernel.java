import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

import MemoryManagement.ProcessMem;
import Process.Process;
import Process.Burst;
import Process.SchedEntity;



public class Kernel 
{		
	public void runKernel(String dataFile, int memSizeKB, int minGran, int targetLat)
	{
		int minGranularity = minGran;
		int targetLatancy = targetLat;
		
		// set up lists to hold processes
		ArrayList<Process> processes = CSV.parseProcessCSV(dataFile);
		ArrayList<Process> onDeck = new ArrayList<Process>();
		ArrayList<Process> killListProc = new ArrayList<Process>();
		
		// set up lists to hold scheduleEntities
		SchedEntity schedEnt = null;
		ArrayList<SchedEntity> waitingIO = new ArrayList<SchedEntity>();
		ArrayList<SchedEntity> killListSched = new ArrayList<SchedEntity>();
		
		
		LockManager lckmgr = new LockManager();
		
		CompletelyFairScheduler cfs = new CompletelyFairScheduler(targetLatancy, minGranularity);
		
		Processor processor = new Processor(lckmgr);
		
		ProcessMem memMan = new ProcessMem();
		memMan.initializeMem(memSizeKB);
		System.out.printf("Initiliazing %dKB (%dMB) of memory\n\n", memSizeKB, memSizeKB/1024);
		
		// set up to run
		int tick = 0;
		
		boolean lockedCriticalSection = false;
		boolean runnableProcess = false;
		
		String printer = "";
		
		// the main event
		do 
		{
			System.out.printf("tick: %d\n", tick);
			
			// find processes to that have arrived, schedule them
			for(Process proc:processes)
			{
				if(proc.arrivalTime == tick)
				{
					memMan.load(proc);
					
					// if the process wasn't into memory
					if(proc.pages.isEmpty())
					{
						onDeck.add(proc);
						System.out.printf("Not enough memory for %s...put on deck\n", proc.name);
					}
					else
					{
						cfs.schedOther(new SchedEntity(proc));
						System.out.printf("Allocated memory for %s...added to the schedule tree\n", proc.name);
						System.out.printf("Memory: %d/%dKB\n", memMan.totalMemorySize() - memMan.memAvailable(), memMan.totalMemorySize());
					}
					
					killListProc.add(proc);
				}
			}
			
			processes.removeAll(killListProc);
			killListProc.clear();
			
			// find processes in the waiting list that are done waiting, schedule them
			for(SchedEntity se:waitingIO)
			{
				if(se.process.bursts.peek().length == 0)
				{
					se.process.bursts.remove();
					cfs.schedOther(se);
					killListSched.add(se);
					
					System.out.printf("Moving %s out of the waiting queue\n", se.process.name);
				}
			}
			
			waitingIO.removeAll(killListSched);
			killListSched.clear();
			
			// advance the time of all the io bursts in the waiting list
			for(SchedEntity se:waitingIO)
			{
				se.process.bursts.peek().length--;
				
				System.out.printf("%s waiting for %d more ticks\n", se.process.name, se.process.bursts.peek().length);
			}
			
			
			if(!processor.hasRunningProcess()) 
			{
				System.out.printf("No process running...");
				
				runnableProcess = false;
				
				// find a the next runnable process on the scheduling tree
				while(!runnableProcess)
				{
					schedEnt = cfs.pickNextTask();
					
					// if the tree is empty, schedEnt will be null
					if(schedEnt != null)
					{
						if((runnableProcess = schedEnt.process.runnable))
						{
							processor.getNewProcess(schedEnt.process);
							System.out.printf("put %s on the processor\n", schedEnt.process.name);
						}
						else
						{
							killListSched.add(schedEnt);
							System.out.printf("\nprocess %s not runnable...", schedEnt.process.name);
						}
					}
					else 
					{
						System.out.printf("no process ready to be put on the processor\n");
						break;
					}
				}
				
				// add all of the not runnable processes back into the schedule tree
				for(SchedEntity se:killListSched)
				{
					cfs.schedOther(se);
				}
				killListSched.clear();
				
			}
			else if(lockedCriticalSection)
			{
				System.out.printf("Process %s tried to enter a locked critical section...", schedEnt.process.name);
				processor.removeProcess();
				
				// if the wait queue for this lock does not exist
				if(!lckmgr.waitQueues.containsKey(schedEnt.process.bursts.peek().lock))
				{
					// create it
					lckmgr.waitQueues.put(new Character(schedEnt.process.bursts.peek().lock), new LinkedList<SchedEntity>());
				}
				
				// add the schedentity to the appropriate wait queue
				lckmgr.waitQueues.get(schedEnt.process.bursts.peek().lock).add(schedEnt);
				
				// set the process to not runnable
				schedEnt.process.runnable = false;
				
				// add the schedentity back into the schedule tree
				cfs.schedOther(schedEnt);
				
				// don't move this print statement...
				System.out.printf("put in waiting queue '%c'\n", schedEnt.process.bursts.peek().lock);
				
				lockedCriticalSection = false;
				schedEnt = null;
			}
			else if(schedEnt.process.bursts.isEmpty())
			{
				System.out.printf("%s finished processing...", schedEnt.process.name);
				
				processor.removeProcess();
				memMan.unload(schedEnt.process);
				schedEnt = null;
				
				System.out.printf("process reaped\n");
				
				// if there is now enough memory for a new process, schedule it
				for(Process proc:onDeck)
				{
					memMan.load(proc);
					
					if(!proc.pages.isEmpty())
					{
						killListProc.add(proc);
						cfs.schedOther(new SchedEntity(proc));
						System.out.printf("Now enough memory for %s...scheduling\n", proc.name);
					}
				}
				
				onDeck.removeAll(killListProc);
				killListProc.clear();
			}
			else if(!schedEnt.process.bursts.peek().cpuBurst)
			{
				System.out.printf("%s is waiting for IO...", schedEnt.process.name);
				
				processor.removeProcess();
				schedEnt.virtualRuntime += processor.getRunTime();
				schedEnt.process.bursts.peek().length--;
				waitingIO.add(schedEnt);
				schedEnt = null;
				
				System.out.printf("placed in wait list\n");
			}
			else if(processor.getRunTime() >= cfs.getTimeSlice())
			{
				System.out.printf("%s overstayed its visit...", schedEnt.process.name);
				
				if(cfs.hasRunnableProcess())
				{
					if(processor.removeProcess())
					{
						schedEnt.virtualRuntime += processor.getRunTime();
						cfs.schedOther(schedEnt);
						schedEnt = null;
						
						System.out.printf("put back in scheduling tree\n");
					}
					else 
					{
						System.out.printf("CPU currently unpreemptable...running process\n");		
						lockedCriticalSection = processor.runProcess();
					}
				}
				else
				{
					System.out.printf("no runnable processes in the tree...running process\n");
					lockedCriticalSection = processor.runProcess();
				}
			}
			else
			{
				lockedCriticalSection = processor.runProcess();
			}
			
			
			printer = "";
			if(processor.hasRunningProcess())
			{
				printer += String.format("Process Running: %s\n", schedEnt.process.name);
				
				if(!schedEnt.process.bursts.isEmpty())
				{
					printer += String.format("Burst Time Remaining: %d\n", schedEnt.process.bursts.peek().length);
					printer += String.format("%s\n", schedEnt.process.bursts.peek().criticalSection ? "Critical Section" : "Not Critical Section");
					printer += String.format("Lock: %c\n", schedEnt.process.bursts.peek().lock);
				}
				else
				{
					printer += String.format("Process Time Remaining: 0\n");
				}
			}
			else printer += String.format("No process running\n");
			
			printer += String.format("Unallocated Memory: %d/%dKB\n", memMan.memAvailable(), memMan.totalMemorySize());
			
			System.out.printf("%s\n", printer);
			
			++tick;			
		} while(processor.hasRunningProcess() || !waitingIO.isEmpty() || !cfs.isEmpty() || !onDeck.isEmpty());
		// while the processor is running a process, there are processes waiting for I/O, or there are processes in the schedule tree
		
		System.out.printf("We are winner\n");
	}
}






