import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Car, Plus, Search, Filter, Clock, CheckCircle, AlertCircle, XCircle, Download, Eye, Edit, Trash2, Shield, Truck } from 'lucide-react';

export function PortalVehicles() {
  const { t } = useTranslation();

  const vehicles = [
    { id: 1, plate: 'A 12345', make: 'Toyota', model: 'Camry', year: 2022, color: 'White', vin: '1HGCM82633A123456', registrationExpiry: '2024-12-15', insuranceExpiry: '2024-12-20', status: 'active' },
    { id: 2, plate: 'B 67890', make: 'Nissan', model: 'Patrol', year: 2020, color: 'Black', vin: 'JN8AZ18T79W123456', registrationExpiry: '2025-03-10', insuranceExpiry: '2025-03-15', status: 'active' },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t('myVehicles') || 'My Vehicles'}</h1>
          <p className="text-text-secondary">{t('vehiclesSubtitle') || 'Manage your vehicle registrations'}</p>
        </div>
        <Link to="/portal/services/vehicles/register" className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
          <Plus className="w-4 h-4" />
          {t('registerVehicle') || 'Register Vehicle'}
        </Link>
      </div>

      <div className="grid grid-cols-1 gap-4">
        {vehicles.map((vehicle) => (
          <Link key={vehicle.id} to={`/portal/vehicles/${vehicle.id}`} className="group p-6 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all duration-300">
            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
              <div className="flex items-center gap-4">
                <div className="p-3 rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400">
                  <Car className="w-6 h-6" />
                </div>
                <div>
                  <h3 className="text-lg font-semibold text-text group-hover:text-primary transition-colors">{vehicle.plate}</h3>
                  <p className="text-text-secondary">{vehicle.year} {vehicle.make} {vehicle.model} • {vehicle.color}</p>
                </div>
              </div>

              <div className="flex flex-col sm:flex-row sm:items-center gap-4">
                <div className="grid grid-cols-3 gap-4 text-center sm:grid-cols-3">
                  <div className="p-3 bg-bg rounded-xl">
                    <p className="text-sm font-medium text-text">{t('registrationExpiry') || 'Registration Expires'}</p>
                    <p className="text-lg font-bold text-text">{new Date(vehicle.registrationExpiry).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}</p>
                  </div>
                  <div className="p-3 bg-bg rounded-xl">
                    <p className="text-sm font-medium text-text">{t('insuranceExpiry') || 'Insurance Expires'}</p>
                    <p className="text-lg font-bold text-text">{new Date(vehicle.insuranceExpiry).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}</p>
                  </div>
                  <div className="p-3 bg-bg rounded-xl">
                    <p className="text-sm font-medium text-text">{t('status')}</p>
                    <p className="text-lg font-bold text-green-600">{vehicle.status === 'active' ? t('active') : t('expired')}</p>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <button className="px-4 py-2 text-sm font-medium text-text-secondary bg-surface border border-border rounded-xl hover:bg-surface-hover hover:border-primary/50 transition-colors">
                    {t('renew') || 'Renew'}
                  </button>
                  <button className="px-4 py-2 text-sm font-medium text-text-secondary bg-surface border border-border rounded-xl hover:bg-surface-hover hover:border-primary/50 transition-colors">
                    {t('transfer') || 'Transfer'}
                  </button>
                </div>
              </div>
            </div>

            <div className="pt-4 border-t border-border hidden sm:block">
              <div className="grid grid-cols-4 gap-4 text-sm">
                <div><p className="text-text-secondary">{t('vin') || 'VIN'}</p><p className="font-medium text-text">{vehicle.vin}</p></div>
                <div><p className="text-text-secondary">{t('color') || 'Color'}</p><p className="font-medium text-text">{vehicle.color}</p></div>
                <div><p className="text-text-secondary">{t('year') || 'Year'}</p><p className="font-medium text-text">{vehicle.year}</p></div>
                <div><p className="text-text-secondary">{t('daysUntilExpiry') || 'Days Left'}</p><p className="font-medium text-text">{Math.ceil((new Date(vehicle.registrationExpiry).getTime() - Date.now()) / (1000 * 60 * 60 * 24))} {t('days')}</p></div>
              </div>
            </div>
          </Link>
        ))}
      </div>

      <div className="text-center p-8 bg-primary/5 rounded-2xl border border-primary/20">
        <h3 className="text-xl font-semibold text-primary mb-2">{t('needNewVehicle') || 'Need to Register a Vehicle?'}</h3>
        <p className="text-primary/80 mb-4">{t('needNewVehicleDesc') || 'Register a new vehicle, transfer ownership, or apply for temporary permits.'}</p>
        <Link to="/portal/services/vehicles/register" className="inline-flex items-center gap-2 px-6 py-3 text-base font-semibold text-primary bg-white rounded-xl hover:bg-primary/10 transition-colors">
          <Plus className="w-4 h-4" />
          {t('registerVehicle') || 'Register Vehicle'}
        </Link>
      </div>
    </div>
  );
}