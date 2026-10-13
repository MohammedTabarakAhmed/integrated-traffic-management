import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Calendar, Plus, Search, Filter, Clock, CheckCircle, AlertCircle, XCircle, MapPin, Building2, User, Bell, MapPin as MapPinIcon, Truck, Car, FileText, Download } from 'lucide-react';
import { useState } from 'react';

export function PortalAppointments() {
  const { t } = useTranslation();
  const [view, setView] = useState<'upcoming' | 'past'>('upcoming');

  const appointments = [
    { id: 1, service: 'Vehicle Inspection', centre: 'Dubai Vehicle Testing Centre - Al Quoz', date: '2024-12-15', time: '10:00 AM', status: 'confirmed', queue: 3, address: 'Al Quoz Industrial Area, Dubai', requirements: ['Vehicle registration', 'Emirates ID', 'Insurance certificate'] },
    { id: 2, service: 'Licence Renewal', centre: 'Deira Licensing Centre', date: '2024-12-20', time: '02:30 PM', status: 'pending', queue: 0, address: 'Deira, Dubai', requirements: ['Emirates ID', 'Eye test certificate', 'Old licence'] },
    { id: 3, service: 'Vehicle Registration', centre: 'Abu Dhabi Vehicle Testing Centre', date: '2024-11-28', time: '09:00 AM', status: 'completed', queue: 0, address: 'Musaffah, Abu Dhabi', requirements: ['Vehicle registration', 'Insurance', 'Emirates ID'] },
    { id: 4, service: 'Violation Payment', centre: 'Deira Traffic Centre', date: '2024-11-20', time: '11:00 AM', status: 'cancelled', queue: 0, address: 'Deira, Dubai', requirements: ['Violation notice', 'Emirates ID'] },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t('myAppointments') || 'My Appointments'}</h1>
          <p className="text-text-secondary">{t('appointmentsSubtitle') || 'Manage your service centre appointments'}</p>
        </div>
        <Link to="/portal/services/appointments/book" className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
          <Plus className="w-4 h-4" />
          {t('bookAppointment') || 'Book Appointment'}
        </Link>
      </div>

      {/* Tabs */}
      <div className="flex gap-2 mb-6 bg-surface rounded-xl p-1 border border-border">
        <button
          onClick={() => setView('upcoming')}
          className={`flex-1 px-4 py-2 rounded-lg text-sm font-medium transition-all ${
            view === 'upcoming'
              ? 'bg-primary text-white shadow-sm'
              : 'text-text-secondary hover:text-text hover:bg-bg'
          }`}
        >
          {t('upcoming') || 'Upcoming'} <span className="ml-2 px-2 py-0.5 text-xs bg-primary/10 text-primary rounded-full">3</span>
        </button>
        <button
          onClick={() => setView('past')}
          className={`flex-1 px-4 py-2 rounded-lg text-sm font-medium transition-all ${
            view === 'past'
              ? 'bg-primary text-white shadow-sm'
              : 'text-text-secondary hover:text-text hover:bg-bg'
          }`}
        >
          {t('past') || 'Past'} <span className="ml-2 px-2 py-0.5 text-xs bg-surface text-text-secondary rounded-full">12</span>
        </button>
      </div>

      {/* Appointments List */}
      <div className="space-y-4">
        {appointments.filter(a => view === 'upcoming' ? ['confirmed', 'pending'].includes(a.status) : ['completed', 'cancelled'].includes(a.status)).map((apt) => (
          <Link key={apt.id} to={`/portal/appointments/${apt.id}`} className="group p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all duration-300">
            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
              <div className="flex items-start sm:items-center gap-4">
                <div className={`p-3 rounded-xl ${apt.status === 'confirmed' ? 'bg-green-50 text-green-600' : apt.status === 'pending' ? 'bg-yellow-50 text-yellow-600' : apt.status === 'completed' ? 'bg-blue-50 text-blue-600' : 'bg-red-50 text-red-600'} dark:bg-opacity-20`}>
                  <Calendar className="w-6 h-6" />
                </div>
                <div>
                  <h3 className="font-semibold text-text group-hover:text-primary transition-colors">{apt.service}</h3>
                  <p className="text-sm text-text-secondary flex items-center gap-2">
                    <MapPinIcon className="w-4 h-4" />
                    {apt.centre}
                  </p>
                </div>
              </div>

              <div className="flex flex-col sm:flex-row sm:items-center gap-4">
                <div className="flex items-center gap-4 text-sm text-text-secondary">
                  <div className="flex items-center gap-1">
                    <Clock className="w-4 h-4" />
                    <span>{apt.time}</span>
                  </div>
                  <div className="flex items-center gap-1">
                    <Calendar className="w-4 h-4" />
                    <span>{new Date(apt.date).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' })}</span>
                  </div>
                </div>

                <div className="flex items-center gap-3">
                  <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium ${
                    apt.status === 'confirmed' ? 'bg-green-50 text-green-700' :
                    apt.status === 'pending' ? 'bg-yellow-50 text-yellow-700' :
                    apt.status === 'completed' ? 'bg-blue-50 text-blue-700' :
                    'bg-red-50 text-red-700'
                  }`}>
                    {t(apt.status) || apt.status}
                  </span>
                  {apt.status === 'confirmed' && apt.queue > 0 && (
                    <span className="px-2.5 py-1 rounded-full text-xs font-medium bg-blue-50 text-blue-700">
                      {t('queuePosition') || 'Queue'} #{apt.queue}
                    </span>
                  )}
                </div>
              </div>
            </div>

            {/* Requirements */}
            <div className="pt-4 border-t border-border hidden sm:block">
              <p className="text-sm font-medium text-text-secondary mb-2">{t('requiredDocuments') || 'Required Documents:'}</p>
              <div className="flex flex-wrap gap-2 mt-2">
                {apt.requirements.map((req, i) => (
                  <span key={i} className="px-2 py-1 text-xs bg-bg rounded text-text-secondary">{req}</span>
                ))}
              </div>
            </div>

            {/* Actions */}
            <div className="flex flex-wrap gap-2 pt-4 border-t border-border">
              {apt.status === 'confirmed' && (
                <>
                  <button className="px-3 py-1.5 text-xs font-medium text-red-600 hover:bg-red-50 rounded-lg transition-colors">
                    {t('cancel') || 'Cancel'}
                  </button>
                  <button className="px-3 py-1.5 text-xs font-medium text-text-secondary bg-surface border border-border rounded-lg hover:bg-surface-hover transition-colors">
                    {t('reschedule') || 'Reschedule'}
                  </button>
                </>
              )}
              {apt.status === 'pending' && (
                <button className="px-3 py-1.5 text-xs font-medium text-text-secondary bg-surface border border-border rounded-lg hover:bg-surface-hover transition-colors">
                  {t('viewDetails') || 'Details'}
                </button>
              )}
              {apt.status === 'completed' && (
                <button className="px-3 py-1.5 text-xs font-medium text-text-secondary bg-surface border border-border rounded-lg hover:bg-surface-hover transition-colors">
                  <Download className="w-3 h-3 mr-1" />
                  {t('receipt') || 'Receipt'}
                </button>
              )}
            </div>
          </Link>
        ))}
      </div>

      {appointments.filter(a => view === 'upcoming' ? ['confirmed', 'pending'].includes(a.status) : ['completed', 'cancelled'].includes(a.status)).length === 0 && (
        <div className="text-center py-12 bg-surface rounded-2xl border border-border">
          <Calendar className="w-12 h-12 text-text-muted mx-auto mb-4" />
          <h3 className="text-lg font-semibold text-text mb-2">{view === 'upcoming' ? t('noUpcomingAppointments') : t('noPastAppointments')}</h3>
          <p className="text-text-muted mb-4">{view === 'upcoming' ? t('noUpcomingDesc') : t('noPastDesc')}</p>
          <Link to="/portal/services/appointments/book" className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
            <Plus className="w-4 h-4" />
            {t('bookAppointment') || 'Book Appointment'}
          </Link>
        </div>
      )}

      {/* Quick Stats */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Link to="/portal/services/appointments/book" className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('bookAppointment') || 'Book Appointment'}</p>
              <p className="text-2xl font-bold text-text mt-1">{t('availableSlots') || 'Available Slots'}</p>
            </div>
            <div className="p-3 rounded-xl bg-primary/10 text-primary">
              <Plus className="w-6 h-6" />
            </div>
          </div>
        </Link>
        <Link to="/portal/appointments/centres" className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('serviceCentres') || 'Service Centres'}</p>
              <p className="text-2xl font-bold text-text mt-1">47 {t('locations')}</p>
            </div>
            <div className="p-3 rounded-xl bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400">
              <Building2 className="w-6 h-6" />
            </div>
          </div>
        </Link>
        <Link to="/portal/appointments/queue" className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('liveQueue') || 'Live Queue'}</p>
              <p className="text-2xl font-bold text-text mt-1">{t('realTimeUpdates') || 'Real-time Updates'}</p>
            </div>
            <div className="p-3 rounded-xl bg-purple-50 text-purple-600 dark:bg-purple-900/20 dark:text-purple-400">
              <Bell className="w-6 h-6" />
            </div>
          </div>
        </Link>
      </div>
    </div>
  );
}